<template>
  <div class="app-root" :class="{ entering, 'on-login': onLogin, 'on-ops': onOps }">
    <AmbientCanvas v-if="!onOps" :intense="onLogin" />

    <div v-if="onLogin" class="login-layer">
      <ThemeToggle class="login-theme" />
      <router-view v-slot="{ Component }">
        <transition name="page" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </div>

    <div v-else-if="onOps" class="ops-frame">
      <router-view v-slot="{ Component }">
        <component :is="Component" />
      </router-view>
    </div>

    <div v-else class="shell" :class="{ reveal: entering }">
      <aside class="sidebar">
        <router-link to="/" class="brand">
          <img class="brand-logo" src="/logo.png" alt="" />
          <div class="brand-copy">
            <div class="brand-mark">智云</div>
            <div class="brand-sub">Multi-Agent 论文审校</div>
          </div>
        </router-link>

        <p class="side-section" aria-hidden="true">工作台</p>
        <nav class="side-nav">
          <router-link to="/" active-class="" exact-active-class="" :class="{ 'is-on': onPapers }">
            <Icon name="papers" /><span>论文</span>
          </router-link>
          <router-link to="/history" active-class="" exact-active-class="" :class="{ 'is-on': onHistory }">
            <Icon name="review" /><span>审校</span>
            <em v-if="unreadInbox" class="nav-badge">{{ unreadInbox > 9 ? '9+' : unreadInbox }}</em>
          </router-link>
          <router-link to="/billing" active-class="" exact-active-class="" :class="{ 'is-on': onBilling }">
            <Icon name="orders" /><span>订单</span>
          </router-link>
          <router-link to="/account" active-class="" exact-active-class="" :class="{ 'is-on': onSettings }">
            <Icon name="settings" /><span>设置</span>
          </router-link>
        </nav>

        <div class="side-engine" aria-hidden="true">
          <div class="side-engine-head">
            <span class="side-engine-dot" />
            <strong>审校引擎</strong>
            <em>7 Agent</em>
          </div>
          <div class="side-engine-rail">
            <i v-for="n in 7" :key="n" :style="{ '--i': n }" />
          </div>
          <p>LiteFlow 编排 · 结构化交接<br />改稿只出候选稿，你确认才写入</p>
        </div>

        <div class="side-foot">
          <router-link
            class="side-account"
            data-testid="side-account"
            :to="{ path: '/account', query: { panel: 'profile' } }"
          >
            <span class="side-avatar" aria-hidden="true">
              <img v-if="avatarSrc" :src="avatarSrc" alt="" />
              <template v-else>{{ initials }}</template>
            </span>
            <span class="side-account-meta">
              <strong>{{ me.displayName || me.email || '我的账户' }}</strong>
              <em>{{ me.email || me.tenantName || '个人实验室' }}</em>
            </span>
          </router-link>
          <button class="side-logout" type="button" @click="logout">
            <Icon name="logout" :size="15" />退出登录
          </button>
        </div>
      </aside>
      <div class="workspace">
        <header class="stage-bar">
          <button
            v-if="showBack"
            class="btn btn-ghost stage-back"
            type="button"
            data-testid="stage-back"
            @click="goBack"
          ><Icon name="arrowLeft" :size="15" />返回</button>
          <div class="stage-crumb" aria-hidden="true">
            <span>智云</span>
            <Icon name="chevronRight" :size="14" />
            <strong>{{ sectionName }}</strong>
          </div>
          <div class="stage-quota">
            <ThemeToggle />
            <QuotaChip compact :quota="me.quota ?? 0" @click="goOrders" />
            <button
              class="btn btn-accent recharge-open"
              type="button"
              data-testid="recharge-open"
              @click="openRecharge"
            ><Icon name="coins" :size="15" />充值</button>
          </div>
        </header>
        <main class="stage">
          <router-view v-slot="{ Component }">
            <transition name="page" mode="out-in">
              <component :is="Component" :key="$route.fullPath" />
            </transition>
          </router-view>
        </main>
      </div>
    </div>

    <div v-if="!onLogin" class="enter-veil" aria-hidden="true" />
    <Chat v-if="!onLogin && !onOps" />
    <ToastHost />
    <RechargeModal v-model:open="rechargeOpen" @paid="loadMe" />
  </div>
</template>

<script setup>
import { computed, onUnmounted, provide, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, token, fetchAvatarBlobUrl } from './api'
import { goBackOrFallback, isShellRoot } from './nav-back'
import { isAuthPublic, isOpsPath } from './auth-guard'
import ToastHost from './components/ToastHost.vue'
import QuotaChip from './components/QuotaChip.vue'
import ThemeToggle from './components/ThemeToggle.vue'
import AmbientCanvas from './components/AmbientCanvas.vue'
import Icon from './components/Icon.vue'
import Chat from './views/Chat.vue'
import RechargeModal from './components/RechargeModal.vue'
import { bootTheme } from './theme'

bootTheme()

const route = useRoute()
const router = useRouter()
const me = ref({})
const avatarSrc = ref('')
const entering = ref(false)
const rechargeOpen = ref(false)
let enterTimer = 0

const onLogin = computed(() => isAuthPublic(route.path))
const onOps = computed(() => isOpsPath(route.path))
const onPapers = computed(() => route.path === '/' || route.path.startsWith('/manuscripts'))
const onHistory = computed(() => route.path === '/history' || route.path.startsWith('/reviews'))
const onBilling = computed(() => route.path === '/billing' || route.path.startsWith('/orders'))
const onSettings = computed(() => route.path === '/account' || route.path.startsWith('/models'))
const unreadInbox = computed(() => Number(me.value.unreadInbox || 0))
const sectionName = computed(() => {
  if (onHistory.value) return route.path.startsWith('/reviews') ? '审校结果' : '审校记录'
  if (onBilling.value) return '订单与额度'
  if (onSettings.value) return '设置'
  return route.path.startsWith('/manuscripts') ? '论文详情' : '论文工作台'
})
const showBack = computed(() => !onLogin.value && !isShellRoot(route))
const initials = computed(() => {
  const name = String(me.value.displayName || me.value.email || '智').trim()
  return name.slice(0, 1)
})

async function loadMe() {
  if (!token() || isAuthPublic(route.path)) {
    me.value = {}
    setAvatar('')
    return
  }
  try {
    me.value = await api('/me')
    if (me.value?.avatarUrl) {
      const url = await fetchAvatarBlobUrl()
      setAvatar(url)
    } else {
      setAvatar('')
    }
  } catch {
    me.value = {}
    setAvatar('')
  }
}

function setAvatar(url) {
  if (avatarSrc.value && avatarSrc.value.startsWith('blob:')) {
    URL.revokeObjectURL(avatarSrc.value)
  }
  avatarSrc.value = url || ''
}

function openRecharge() {
  rechargeOpen.value = true
}

provide('refreshMe', loadMe)
provide('avatarSrc', avatarSrc)
provide('me', me)
provide('openRecharge', openRecharge)

function armEnter() {
  if (isAuthPublic(route.path) || onOps.value || sessionStorage.getItem('zhiyun-enter') !== '1') return
  sessionStorage.removeItem('zhiyun-enter')
  entering.value = true
  clearTimeout(enterTimer)
  enterTimer = window.setTimeout(() => {
    entering.value = false
  }, 1680)
}

watch(() => route.fullPath, () => {
  armEnter()
  loadMe()
}, { immediate: true })

onUnmounted(() => {
  clearTimeout(enterTimer)
  setAvatar('')
})

function goOrders() {
  router.push('/billing')
}

function goBack() {
  goBackOrFallback(router, route)
}

function logout() {
  sessionStorage.removeItem('token')
  sessionStorage.removeItem('zhiyun-enter')
  router.push('/login')
}
</script>
