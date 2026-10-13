<template>
  <div class="billing-page">
    <div class="billing-head">
      <div>
        <p class="page-kicker">订单</p>
        <h1 class="page-title">额度流水</h1>
        <p class="page-lead">充值请点右上角。这里只看额度进出。</p>
      </div>
      <div class="balance-card">
        <span class="balance-label"><Icon name="coins" :size="14" />当前可用额度</span>
        <strong class="balance-num">{{ quota }}</strong>
        <span class="balance-hint">{{ quotaHint(quota) }}</span>
        <button class="btn btn-accent" type="button" @click="openRecharge"><Icon name="plus" :size="15" />充值额度</button>
      </div>
    </div>

    <ul class="billing-rules stagger">
      <li><span><Icon name="coins" :size="16" /></span><div><strong>1 元到账 1 额度</strong><em>套餐另有赠送，最低 10 元</em></div></li>
      <li><span><Icon name="cpu" :size="16" /></span><div><strong>按实际 token 结算</strong><em>约每 2000 token 计 1 额度，不超过上限</em></div></li>
      <li><span><Icon name="shield" :size="16" /></span><div><strong>同一任务只结算一次</strong><em>失败续跑不会重复扣费</em></div></li>
    </ul>

    <QuotaLedger :refresh-token="orderTick" />

    <Teleport to="body">
      <div v-if="detailId" class="overlay drawer-overlay" @click.self="closeDetail">
        <aside class="drawer" role="dialog" aria-modal="true" aria-labelledby="order-drawer-title">
          <OrderDetail :order-id="detailId" embedded @close="closeDetail" @paid="onOrderPaid" />
        </aside>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import QuotaLedger from '../components/QuotaLedger.vue'
import Icon from '../components/Icon.vue'
import { quotaHint } from '../labels'
import OrderDetail from './OrderDetail.vue'

const route = useRoute()
const router = useRouter()
const refreshMe = inject('refreshMe', () => Promise.resolve())
const openRecharge = inject('openRecharge', () => {})
const orderTick = ref(0)
const shellMe = inject('me', null)
const me = ref({})
const quota = computed(() => Number(shellMe?.value?.quota ?? me.value.quota ?? 0))

watch(() => shellMe?.value?.quota, (next, prev) => {
  if (prev !== undefined && next !== prev) orderTick.value += 1
})
const detailId = computed(() => {
  const raw = route.query.order
  const id = Array.isArray(raw) ? raw[0] : raw
  return id ? String(id) : ''
})

onMounted(() => {
  load()
  window.addEventListener('keydown', onKey)
})
onUnmounted(() => window.removeEventListener('keydown', onKey))

function onKey(e) {
  if (e.key === 'Escape' && detailId.value) closeDetail()
}

async function load() {
  me.value = await api('/me')
  await refreshMe()
}

function closeDetail() {
  router.replace({ path: '/billing' })
}

async function onOrderPaid() {
  orderTick.value += 1
  await load()
}
</script>
