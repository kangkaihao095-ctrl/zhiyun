<template>
  <div class="cs-dock">
    <transition name="cs-pop">
      <section v-if="open" class="cs-panel" role="dialog" :aria-label="CS_NAME" aria-modal="true">
        <header class="cs-head">
          <div>
            <p class="cs-kicker">{{ CS_NAME }}</p>
            <h2>有问题问{{ CS_NAME }}</h2>
          </div>
          <button type="button" class="cs-x" :aria-label="'关闭' + CS_NAME" @click="open = false">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M6 6l12 12M18 6 6 18" />
            </svg>
          </button>
        </header>
        <div class="cs-faqs" aria-label="常见问题">
          <button type="button" v-for="s in suggestions" :key="s" @click="ask(s)">{{ s }}</button>
        </div>
        <div class="transcript cs-transcript" ref="scroller">
          <div v-if="!shown.length && !(busy && !streaming)" class="cs-empty">
            我是{{ CS_NAME }}，智云的纸笺助手。点上面的问题，或在下面输入。
          </div>
          <div v-for="(m, i) in shown" :key="i" class="msg" :class="m.role">
            <div v-if="m.role === 'assistant'" class="msg-body" v-html="formatAssistant(m.content)" />
            <template v-else>{{ m.content }}</template>
          </div>
          <div v-if="busy && !streaming" class="msg assistant thinking" aria-live="polite">
            <span class="think-orb" aria-hidden="true" />
            <span class="think-copy">
              <span class="think-dots" aria-hidden="true"><i /><i /><i /></span>
              <span class="think-label">正在想怎么回答你</span>
            </span>
          </div>
        </div>
        <form class="composer cs-composer" @submit.prevent="send">
          <input
            ref="box"
            v-model="draft"
            placeholder="输入问题，回车发送"
          />
          <button class="btn btn-accent" type="submit" :disabled="busy">
            <span v-if="busy" class="send-wait"><i /><i /><i /></span>
            <span v-else>发送</span>
          </button>
        </form>
      </section>
    </transition>
    <button
      type="button"
      class="cs-fab"
      :class="{ on: open }"
      :aria-expanded="open"
      :aria-label="open ? '关闭' + CS_NAME : '打开' + CS_NAME"
      @click="open = !open"
    >
      <svg v-if="!open" class="cs-fab-ico" viewBox="0 0 24 24" aria-hidden="true">
        <path d="M5 6.5A2.5 2.5 0 0 1 7.5 4h9A2.5 2.5 0 0 1 19 6.5v7A2.5 2.5 0 0 1 16.5 16H12l-4 3.2V16H7.5A2.5 2.5 0 0 1 5 13.5v-7Z" />
        <path d="M9 9.2h6M9 12.2h4" />
      </svg>
      <svg v-else class="cs-fab-ico" viewBox="0 0 24 24" aria-hidden="true">
        <path d="M6 6l12 12M18 6 6 18" />
      </svg>
    </button>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { token } from '../api'
import { formatAssistant } from '../cs-format'
import { CS_NAME } from '../labels'
import { chatRequestBody } from '../cs-memory'

const pool = [
  '我的额度还剩多少？',
  '最低充多少钱？',
  '套餐比自己充更便宜吗？',
  '引用核验会改我的正文吗？',
  '完整审校改完需要我确认吗？',
  'ACL 用 A4 还是 Letter？',
  'NeurIPS 能不能交 Word？',
  'IEEE 对图片清晰度有什么要求？',
  '一次审校大概扣多少额度？',
  '审校失败会退额度吗？',
  '怎么看以前的审校记录？',
  '查一下我的历史充值记录',
  '看下我过去花了多少钱了',
  '我的审校任务进度',
  '订单支付了额度却没到怎么办？',
  '假的 DOI 会怎么标记？',
  'Nature 接受 Word 稿吗？',
  '云笺能帮我改额度吗？',
  '快速审读会不会改正文？',
  '常用套餐多少钱、到账多少额度？',
  '怎么给账户充值？'
]

const open = ref(false)
const suggestions = ref([])
/** 本页会话，只在内存；刷新或关页即新会话，不写 localStorage / sessionStorage。 */
const messages = ref([])
const draft = ref('')
const streaming = ref('')
const busy = ref(false)
const scroller = ref(null)
const box = ref(null)
let abort = null

const shown = computed(() => {
  const list = [...messages.value]
  if (streaming.value) list.push({ role: 'assistant', content: streaming.value })
  return list
})

onMounted(() => {
  const copy = [...pool]
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[copy[i], copy[j]] = [copy[j], copy[i]]
  }
  suggestions.value = copy.slice(0, 4)
  window.addEventListener('keydown', onKey)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKey)
  abort?.abort()
})

function onKey(e) {
  if (e.key === 'Escape') open.value = false
}

watch(open, async (v) => {
  if (!v) return
  await nextTick()
  box.value?.focus()
})

watch(shown, async () => {
  await nextTick()
  const el = scroller.value
  if (el) el.scrollTop = el.scrollHeight
})


function ask(text) {
  if (busy.value) return
  draft.value = text
  send()
}

function parseSse(part) {
  const lines = part.replace(/\r/g, '').split('\n')
  let event = 'message'
  const chunks = []
  for (const line of lines) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
      continue
    }
    if (!line.startsWith('data:')) continue
    let raw = line.startsWith('data: ') ? line.slice(6) : line.slice(5)
    if (raw === '[DONE]' || raw === '"[DONE]"') {
      event = 'done'
      continue
    }
    try {
      const parsed = JSON.parse(raw)
      chunks.push(typeof parsed === 'string' ? parsed : raw)
    } catch {
      chunks.push(raw)
    }
  }
  return { event, data: chunks.join('') }
}

async function send() {
  const user = draft.value.trim()
  if (!user || busy.value) return
  messages.value.push({ role: 'user', content: user })
  draft.value = ''
  const payload = chatRequestBody(messages.value)
  streaming.value = ''
  busy.value = true
  abort = new AbortController()
  const timer = window.setTimeout(() => abort?.abort(), 90_000)
  try {
    const res = await fetch('/api/cs/chat', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        Authorization: 'Bearer ' + token()
      },
      body: JSON.stringify(payload),
      signal: abort.signal
    })
    if (!res.ok) {
      throw new Error(CS_NAME + '暂时忙，请稍后再试')
    }
    if (!res.body) {
      throw new Error(CS_NAME + '没有返回内容，请再问一次')
    }
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buf = ''
    let text = ''
    let finished = false
    while (!finished) {
      const { done, value } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      const parts = buf.split(/\n\n/)
      buf = parts.pop() ?? ''
      for (const part of parts) {
        const parsed = parseSse(part)
        if (parsed.event === 'done') {
          finished = true
          try { await reader.cancel() } catch { /* ignore */ }
          break
        }
        if (!parsed.data) continue
        text += parsed.data
        streaming.value = text
      }
    }
    if (!finished && buf.trim()) {
      const parsed = parseSse(buf)
      if (parsed.event !== 'done' && parsed.data) text += parsed.data
    }
    messages.value.push({ role: 'assistant', content: text || '这次没有收到完整答复，请再问一次。' })
  } catch (e) {
    if (e.name === 'AbortError') {
      messages.value.push({ role: 'assistant', content: '这次回答超时了，请再问一次。' })
    } else {
      messages.value.push({ role: 'assistant', content: e.message || CS_NAME + '暂时不可用。' })
    }
  } finally {
    window.clearTimeout(timer)
    streaming.value = ''
    busy.value = false
    await nextTick()
    box.value?.focus()
  }
}
</script>
