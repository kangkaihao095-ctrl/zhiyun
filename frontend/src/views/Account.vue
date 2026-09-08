<template>
  <div class="account-page">
    <template v-if="!panel">
      <p class="page-kicker">设置</p>
      <h1 class="page-title">设置</h1>
      <p class="page-lead">
        账户和模型分项打开。云笺和检索由平台提供，不能更换。充值请点右上角，流水在
        <router-link class="text-link" to="/billing">订单</router-link>。
      </p>
      <div class="settings-hub">
        <button type="button" class="settings-card" @click="openPanel('profile')">
          <p class="panel-label">账户</p>
          <h3>账户资料</h3>
          <p class="muted">显示名、头像和实验室信息。改名需确认。</p>
        </button>
        <button type="button" class="settings-card" @click="openPanel('models')">
          <p class="panel-label">模型</p>
          <h3>模型配置</h3>
          <p class="muted">论文 7 Agent 可覆盖平台默认。云笺和检索不可更换。</p>
        </button>
        <button type="button" class="settings-card" @click="goOrders">
          <p class="panel-label">订单</p>
          <h3>额度流水</h3>
          <p class="muted">打开订单页看额度进出。充值请点右上角。</p>
        </button>
        <button
          v-if="me.operator"
          type="button"
          class="settings-card"
          @click="openPanel('knowledge')"
        >
          <p class="panel-label">运营</p>
          <h3>公共知识运营</h3>
          <p class="muted">查看公共知识精华、上传规范并重建索引。仅运营可见。</p>
        </button>
      </div>
    </template>

    <template v-else>
      <p class="page-kicker">
        <button type="button" class="text-link settings-back" @click="closePanel">设置</button>
        <span> / {{ panelTitle }}</span>
      </p>
      <h1 class="page-title">{{ panelTitle }}</h1>
      <p class="page-lead">{{ panelLead }}</p>

      <section v-if="panel === 'profile'" class="panel account-hero">
        <div class="avatar-col">
          <div class="avatar">
            <img v-if="avatarSrc" :src="avatarSrc" alt="" />
            <template v-else>{{ initials }}</template>
          </div>
          <label class="btn btn-ghost avatar-upload">
            上传头像
            <input class="file-hidden" type="file" accept="image/jpeg,image/png,image/webp" @change="onAvatar" />
          </label>
          <p class="muted avatar-hint">jpg / png / webp，最大 2MB</p>
        </div>
        <div class="account-edit">
          <div class="account-name-row">
            <span class="account-field-label">显示名</span>
            <strong class="account-name-now" data-testid="display-name">{{ me.displayName || '—' }}</strong>
            <button
              v-if="!editingName"
              class="btn btn-ghost"
              type="button"
              data-testid="edit-display-name"
              @click="beginEditName"
            >修改显示名</button>
          </div>
          <form v-if="editingName" class="name-edit" @submit.prevent="saveDisplayName">
            <p class="muted">当前显示名：{{ originalName || '—' }}</p>
            <label>
              <span>新显示名</span>
              <input
                ref="nameInput"
                class="field"
                v-model="nameDraft"
                maxlength="128"
                data-testid="display-name-input"
              />
            </label>
            <div class="row">
              <button class="btn btn-accent" type="submit" :disabled="saving || !nameChanged">
                {{ saving ? '保存中…' : '保存' }}
              </button>
              <button class="btn btn-ghost" type="button" :disabled="saving" @click="cancelEditName">取消</button>
            </div>
          </form>
          <dl class="account-kv">
            <dt>邮箱</dt><dd>{{ me.email || '—' }}</dd>
            <dt>用户编号</dt><dd>{{ me.userId ?? '—' }}</dd>
            <dt>实验室</dt><dd>{{ me.tenantName || '—' }}</dd>
            <dt>实验室编号</dt><dd>{{ me.tenantId ?? '—' }}</dd>
            <dt>加入时间</dt><dd>{{ formatTime(me.createdAt) }}</dd>
          </dl>
          <p class="err" v-if="profileError">{{ profileError }}</p>
        </div>
      </section>

      <template v-else-if="panel === 'models'">
        <Models />
      </template>

      <template v-else-if="panel === 'knowledge' && me.operator">
        <Knowledge :me="me" />
      </template>
    </template>
  </div>
</template>

<script setup>
import { computed, inject, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { formatTime } from '../labels'
import { toast } from '../toast'
import Models from './Models.vue'
import Knowledge from './Knowledge.vue'

const PANELS = {
  profile: {
    title: '账户资料',
    lead: '显示名只在这里改，确认后才提交。侧栏和顶栏只展示。'
  },
  models: {
    title: '模型配置',
    lead: '点开某一个论文 Agent，改成平台默认或填自己的 API Key。云笺和检索由平台提供，不可更换。'
  },
  knowledge: {
    title: '公共知识运营',
    lead: '更新的是全局 PUBLIC 知识，不是每个实验室一份。默认看精华概述，目录只列专章标题。'
  }
}

const route = useRoute()
const router = useRouter()
const refreshMe = inject('refreshMe', () => Promise.resolve())
const avatarSrc = inject('avatarSrc', ref(''))
const me = ref({})
const nameDraft = ref('')
const originalName = ref('')
const editingName = ref(false)
const saving = ref(false)
const profileError = ref('')
const nameInput = ref(null)

const panel = computed(() => {
  const raw = String(route.query.panel || '')
  if (raw === 'knowledge' && !me.value.operator) return ''
  return PANELS[raw] ? raw : ''
})
const panelTitle = computed(() => PANELS[panel.value]?.title || '设置')
const panelLead = computed(() => PANELS[panel.value]?.lead || '')
const initials = computed(() => {
  const name = String(me.value.displayName || me.value.email || '智').trim()
  return name.slice(0, 1)
})
const nameChanged = computed(() => nameDraft.value.trim() !== originalName.value.trim() && nameDraft.value.trim() !== '')

onMounted(async () => {
  await load()
  normalizeEntry()
})

watch(() => [route.query.panel, route.hash], () => {
  normalizeEntry()
})

function normalizeEntry() {
  if (String(route.query.panel) === 'quota' || route.hash === '#ledger') {
    router.replace('/billing')
    return
  }
  if (route.hash === '#models') {
    router.replace({ path: '/account', query: { panel: 'models' } })
    return
  }
  if (route.hash === '#knowledge' && me.value.operator) {
    router.replace({ path: '/account', query: { panel: 'knowledge' } })
  }
}

function goOrders() {
  router.push('/billing')
}

function openPanel(id) {
  router.push({ path: '/account', query: { panel: id } })
}

function closePanel() {
  editingName.value = false
  profileError.value = ''
  router.push({ path: '/account' })
}

async function load() {
  me.value = await api('/me')
  originalName.value = me.value.displayName || ''
  nameDraft.value = originalName.value
  await refreshMe()
}

function beginEditName() {
  originalName.value = me.value.displayName || ''
  nameDraft.value = originalName.value
  editingName.value = true
  profileError.value = ''
  nextTick(() => nameInput.value?.focus())
}

function cancelEditName() {
  nameDraft.value = originalName.value
  editingName.value = false
  profileError.value = ''
}

async function saveDisplayName() {
  const next = nameDraft.value.trim()
  if (!next || next === originalName.value.trim()) return
  saving.value = true
  profileError.value = ''
  try {
    me.value = await api('/me', {
      method: 'PUT',
      body: { displayName: next }
    })
    originalName.value = me.value.displayName || ''
    nameDraft.value = originalName.value
    editingName.value = false
    await refreshMe()
    toast('显示名已更新')
  } catch (e) {
    profileError.value = e.message || '保存失败'
    toast(profileError.value, 'err')
  } finally {
    saving.value = false
  }
}

async function onAvatar(e) {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (!file) return
  if (file.size > 2 * 1024 * 1024) {
    toast('头像不能超过 2MB', 'err')
    return
  }
  const fd = new FormData()
  fd.append('file', file)
  try {
    await api('/me/avatar', { method: 'PUT', body: fd })
    await refreshMe()
    me.value = await api('/me')
    toast('头像已更新')
  } catch (err) {
    toast(err.message || '头像上传失败', 'err')
  }
}
</script>
