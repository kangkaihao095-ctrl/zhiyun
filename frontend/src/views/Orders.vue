<template>
  <section id="orders" class="panel" style="margin-top:18px">
    <p class="panel-label">订单</p>
    <h3>我的订单</h3>
    <p class="muted" style="margin: 0 0 12px">查单、复制订单号，或打开详情。还没到账的可以继续支付。</p>
    <ListPager
      v-model:q="q"
      v-model:page="page"
      v-model:size="size"
      :total="total"
      placeholder="订单号、套餐名或状态"
      @search="search"
    />
    <div v-if="!orders.length" class="empty-block">
      还没有订单。在右上角充值后会出现。
    </div>
    <div v-for="o in orders" :key="o.id" class="order-card">
      <button class="order-main" type="button" @click="emit('open', o.id)">
        <p class="order-title">{{ o.planName || '灵活充值' }}</p>
        <p class="order-id">
          订单号 <span>{{ o.id }}</span>
        </p>
        <p class="muted">{{ o.kind === 'CUSTOM' ? '按金额充值' : '套餐' }} · {{ formatTime(o.createdAt) }}</p>
      </button>
      <div class="order-side">
        <div class="order-meta">
          <span>{{ o.quotaAmount }} 额度</span>
          <span>¥{{ yuan(o.amountCents) }}</span>
          <span class="order-status" :class="o.status === 'PAID' ? 'ok' : 'wait'">{{ statusText(o.status) }}</span>
        </div>
        <div class="order-actions">
          <button class="btn btn-ghost" type="button" @click="copyId(o.id)">复制单号</button>
          <button class="btn btn-ghost" type="button" @click="emit('open', o.id)">详情</button>
          <button
            v-if="o.status === 'PENDING'"
            class="btn btn-accent"
            type="button"
            :disabled="paying"
            @click="payExisting(o)"
          >继续支付</button>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { inject, onMounted, ref, watch } from 'vue'
import { api, asPage, DEFAULT_PAGE_SIZE, qs } from '../api'
import { toast } from '../toast'
import { copyText, formatTime, yuan } from '../labels'
import ListPager from '../components/ListPager.vue'

const emit = defineEmits(['open', 'paid'])

const props = defineProps({
  refreshToken: { type: Number, default: 0 }
})

const refreshMe = inject('refreshMe', () => Promise.resolve())
const orders = ref([])
const q = ref('')
const page = ref(1)
const size = ref(DEFAULT_PAGE_SIZE)
const total = ref(0)
const paying = ref(false)
const me = ref({})

onMounted(load)
watch([page, size], load)
watch(() => props.refreshToken, load)

function search() {
  page.value = 1
  load()
}

function statusText(status) {
  if (status === 'PAID') return '已到账'
  if (status === 'CANCELLED') return '已取消'
  return '待支付'
}

async function waitPaid(orderId, first) {
  if (first?.status === 'PAID') return first
  for (let i = 0; i < 12; i++) {
    await new Promise((r) => setTimeout(r, 250))
    const row = await api('/orders/' + orderId)
    if (row?.status === 'PAID') return row
  }
  return first
}

async function copyId(id) {
  const ok = await copyText(id)
  toast(ok ? '已复制订单号' : '复制失败，请手动选中', ok ? 'ok' : 'err')
}

async function load() {
  me.value = await api('/me')
  const data = asPage(await api('/orders' + qs({ q: q.value, page: page.value, size: size.value })))
  orders.value = data.items
  total.value = data.total
  page.value = data.page
  size.value = data.size
  await refreshMe()
}

async function payExisting(order) {
  paying.value = true
  const before = Number(me.value.quota ?? 0)
  try {
    const paid = await api('/orders/' + order.id + '/mock-pay', { method: 'POST', body: {} })
    const settled = await waitPaid(order.id, paid)
    await load()
    const after = Number(me.value.quota ?? 0)
    if (settled.status === 'PAID') {
      toast(`订单已到账。额度 ${before} → ${after}。`)
      emit('paid')
    } else {
      toast('还没有到账，请稍后再试。', 'err')
    }
  } catch (e) {
    toast(e.message || '支付没有成功', 'err')
  } finally {
    paying.value = false
  }
}
</script>
