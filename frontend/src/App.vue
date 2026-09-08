<template>
  <div class="app-root" :class="{ entering, 'on-login': onLogin }">
    <AmbientCanvas :intense="onLogin || entering" />
    <div class="grain" aria-hidden="true" />
    <div class="light-sheet" aria-hidden="true" />

    <div v-if="onLogin" class="login-layer">
      <router-view v-slot="{ Component }">
        <transition name="page" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </div>

    <div v-else class="shell" :class="{ reveal: entering }">
      <aside class="sidebar">
        <router-link to="/" class="brand">
          <img class="brand-logo" src="/logo.png" alt="" />
          <div class="brand-copy">
            <div class="brand-mark">智云</div>
            <div class="brand-sub">论文审校</div>
          </div>
        </router-link>
        <nav class="side-nav">
          <router-link to="/" active-class="" exact-active-class="" :class="{ 'is-on': onPapers }">论文</router-link>
          <router-link to="/history" active-class="" exact-active-class="" :class="{ 'is-on': onHistory }">
            审校
            <em v-if="unreadInbox" class="nav-badge">{{ unreadInbox > 9 ? '9+' : unreadInbox }}</em>
          </router-link>
          <router-link to="/billing" active-class="" exact-active-class="" :class="{ 'is-on': onBilling }">订单</router-link>
          <router-link to="/account" active-class="" exact-active-class="" :class="{ 'is-on': onSettings }">设置</router-link>
        </nav>
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
          <button class="side-logout" type="button" @click="logout">退出登录</button>
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
          >返回</button>
          <div class="stage-quota">
            <QuotaChip compact :quota="me.quota ?? 0" @click="goOrders" />
            <button
              class="btn btn-accent recharge-open"
              type="button"
              data-testid="recharge-open"
              @click="openRecharge"
            >充值</button>
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

    <div class="enter-veil" aria-hidden="true" />
    <Chat v-if="!onLogin" />
    <ToastHost />
    <RechargeModal v-model:open="rechargeOpen" @paid="loadMe" />
  </div>
</template>

<script setup>
import { computed, onUnmounted, provide, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, token, fetchAvatarBlobUrl } from './api'
import { goBackOrFallback, isShellRoot } from './nav-back'
import ToastHost from './components/ToastHost.vue'
import QuotaChip from './components/QuotaChip.vue'
import AmbientCanvas from './components/AmbientCanvas.vue'
import Chat from './views/Chat.vue'
import RechargeModal from './components/RechargeModal.vue'

const route = useRoute()
const router = useRouter()
const me = ref({})
const avatarSrc = ref('')
const entering = ref(false)
const rechargeOpen = ref(false)
let enterTimer = 0

const onLogin = computed(() => route.path === '/login')
const onPapers = computed(() => route.path === '/' || route.path.startsWith('/manuscripts'))
const onHistory = computed(() => route.path === '/history' || route.path.startsWith('/reviews'))
const onBilling = computed(() => route.path === '/billing' || route.path.startsWith('/orders'))
const onSettings = computed(() => route.path === '/account' || route.path.startsWith('/models'))
const unreadInbox = computed(() => Number(me.value.unreadInbox || 0))
const showBack = computed(() => !onLogin.value && !isShellRoot(route))
const initials = computed(() => {
  const name = String(me.value.displayName || me.value.email || '智').trim()
  return name.slice(0, 1)
})

async function loadMe() {
  if (!token() || route.path === '/login') {
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
  if (route.path === '/login' || sessionStorage.getItem('zhiyun-enter') !== '1') return
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
