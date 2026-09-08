<template>
  <div>
    <p class="page-kicker">订单</p>
    <h1 class="page-title">额度流水</h1>
    <p class="page-lead">充值请点右上角。这里只看额度进出。</p>

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
import { computed, inject, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import QuotaLedger from '../components/QuotaLedger.vue'
import OrderDetail from './OrderDetail.vue'

const route = useRoute()
const router = useRouter()
const refreshMe = inject('refreshMe', () => Promise.resolve())
const orderTick = ref(0)
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
  await api('/me')
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
