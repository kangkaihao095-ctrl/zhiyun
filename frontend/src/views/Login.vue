<template>
  <div class="login-shell" :class="{ busy, 'is-ops': opsMode }">
    <section class="login-story">
      <div class="login-brand-row">
        <img class="brand-logo lg" src="/logo.png" alt="智云" />
        <div>
          <div class="brand-mark login-brand">智云</div>
          <p class="brand-sub">{{ opsMode ? '内部观测' : '论文审校' }}</p>
        </div>
      </div>

      <p class="login-eyebrow">
        <Icon name="sparkles" :size="14" />
        {{ opsMode ? 'Harness 运行观测 · 时间窗聚合' : 'Multi-Agent 论文审校 SaaS' }}
      </p>
      <h2 v-if="!opsMode" class="login-headline">
        让每一条引用、每一张图、<br />每一句话，<em>经得起审稿人追问。</em>
      </h2>
      <h2 v-else class="login-headline">
        看清每一次审校<br /><em>在哪个 Agent 花了时间。</em>
      </h2>
      <blockquote>上传论文，检查引用、图表和文字。</blockquote>
      <p class="muted">有修改会先给你看，确认后才写进正文。额度按实际用量扣。</p>

      <div class="login-pipe">
        <AgentPipeline mode="showcase" compact />
      </div>

      <ul v-if="!opsMode" class="login-points stagger">
        <li>
          <span class="login-point-ico"><Icon name="quote" /></span>
          <span><strong>引用不靠模型口算</strong>Crossref 逐条核验 DOI，查无实据标记 NOT_VERIFIED</span>
        </li>
        <li>
          <span class="login-point-ico"><Icon name="shield" /></span>
          <span><strong>改稿只出候选稿</strong>逐处对照，你接受之后才写入正式稿</span>
        </li>
        <li>
          <span class="login-point-ico"><Icon name="refresh" /></span>
          <span><strong>长任务可恢复</strong>失败可从已完成节点续跑，同一任务只结算一次</span>
        </li>
      </ul>
      <ul v-else class="login-points stagger">
        <li>
          <span class="login-point-ico"><Icon name="activity" /></span>
          <span><strong>L1 总览</strong>窗口内创建、完成、积压与耗时分位</span>
        </li>
        <li>
          <span class="login-point-ico"><Icon name="layers" /></span>
          <span><strong>L2 单任务瀑布</strong>按 taskId + Agent 展开每个节点</span>
        </li>
        <li>
          <span class="login-point-ico"><Icon name="cpu" /></span>
          <span><strong>L3 领域指标</strong>LLM、引用核验、RAG 空召回、Harness</span>
        </li>
      </ul>
    </section>
    <section class="login-card">
      <form class="login-form" @submit.prevent="submit">
        <p class="page-kicker">{{ opsMode ? '内部' : '账户' }}</p>
        <h1>{{ opsMode ? '观测台' : (mode === 'login' ? '登录' : '注册') }}</h1>
        <p class="muted">{{ opsLead }}</p>
        <div v-if="!opsMode" class="auth-tabs" role="tablist">
          <button
            type="button"
            role="tab"
            class="auth-tab"
            :class="{ on: mode === 'login' }"
            :aria-selected="mode === 'login'"
            @click="mode = 'login'"
          >
            <svg class="auth-ico" viewBox="0 0 24 24" aria-hidden="true">
              <path d="M10 7V5.8A1.8 1.8 0 0 1 11.8 4h6.4A1.8 1.8 0 0 1 20 5.8v12.4A1.8 1.8 0 0 1 18.2 20h-6.4A1.8 1.8 0 0 1 10 18.2V17" />
              <path d="M4 12h11" />
              <path d="M12 8.5 15.5 12 12 15.5" />
            </svg>
            登录
          </button>
          <button
            type="button"
            role="tab"
            class="auth-tab"
            :class="{ on: mode === 'register' }"
            :aria-selected="mode === 'register'"
            @click="mode = 'register'"
          >
            <svg class="auth-ico" viewBox="0 0 24 24" aria-hidden="true">
              <circle cx="10" cy="8" r="3.2" />
              <path d="M4.6 19c.7-3.1 3-4.8 5.4-4.8 1.4 0 2.7.6 3.7 1.5" />
              <path d="M17 11v6" />
              <path d="M14 14h6" />
            </svg>
            注册
          </button>
        </div>
        <label class="login-field">
          <span>邮箱</span>
          <input class="field" v-model="email" type="email" autocomplete="username" placeholder="name@lab.edu" required />
        </label>
        <label class="login-field">
          <span>密码</span>
          <input class="field" v-model="password" type="password" autocomplete="current-password" placeholder="至少 6 位" required />
        </label>
        <transition name="field-pop">
          <label v-if="mode==='register' && !opsMode" class="login-field">
            <span>称呼</span>
            <input class="field" v-model="displayName" placeholder="怎么称呼你" required />
          </label>
        </transition>
        <button class="btn btn-accent btn-lg login-go" type="submit" :disabled="busy">
          <span v-if="busy" class="spinner" aria-hidden="true" />
          <span>{{ busy ? '正在进入…' : (opsMode ? '进入观测台' : (mode === 'login' ? '进入' : '注册并进入')) }}</span>
          <Icon v-if="!busy" name="arrowRight" :size="16" />
        </button>
        <p class="err" v-if="error">{{ error }}</p>
        <p v-if="mode === 'login'" class="login-demo">
          <Icon name="key" :size="13" />
          演示账号 demo@zhiyun.dev 已预填，可直接进入
        </p>
      </form>
    </section>
    <router-link
      v-if="!opsMode"
      class="login-ops-entry"
      to="/ops/login"
      data-testid="login-ops-entry"
    >观测台</router-link>
    <router-link
      v-else
      class="login-ops-entry"
      to="/login"
      data-testid="login-ops-back"
    >返回登录</router-link>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import Icon from '../components/Icon.vue'
import AgentPipeline from '../components/AgentPipeline.vue'

const route = useRoute()
const router = useRouter()
const opsMode = computed(() => route.path === '/ops/login' || route.meta?.ops === true)
const opsLead = computed(() => {
  if (opsMode.value) return '管理员登录后查看全部实验室运行观测。非 SLA。'
  return mode.value === 'login' ? '用邮箱登录后，可以上传论文、查看审校记录。' : '注册后送 3 额度，可以先试一次。'
})
const mode = ref('login')
const email = ref('demo@zhiyun.dev')
const password = ref('demo123456')
const displayName = ref('一只用户')
const error = ref('')
const busy = ref(false)

function prefersReduce() {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

async function submit() {
  error.value = ''
  busy.value = true
  try {
    const ops = opsMode.value
    const path = ops ? '/auth/ops/login' : (mode.value === 'login' ? '/auth/login' : '/auth/register')
    const body = (!ops && mode.value === 'register')
      ? { email: email.value, password: password.value, displayName: displayName.value }
      : { email: email.value, password: password.value }
    const data = await api(path, { method: 'POST', body })
    sessionStorage.setItem('token', data.token)
    const dest = ops ? '/ops' : '/'
    if (!ops && !prefersReduce()) sessionStorage.setItem('zhiyun-enter', '1')
    router.push(dest)
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
</script>
