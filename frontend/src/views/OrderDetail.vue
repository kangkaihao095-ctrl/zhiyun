<template>
  <div :class="{ 'order-panel': embedded }">
    <div :class="{ 'drawer-head': embedded }">
      <div>
        <p class="page-kicker">订单</p>
        <h2 v-if="embedded" id="order-drawer-title" class="page-title">订单详情</h2>
        <h1 v-else class="page-title">订单详情</h1>
      </div>
      <button v-if="embedded" class="btn btn-ghost" type="button" @click="$emit('close')">关闭</button>
    </div>
    <p v-if="!embedded" class="page-lead">
      这里只展示这张订单。额度流水请到
      <router-link class="text-link" to="/billing">订单</router-link> 查看。
    </p>
    <p v-else class="muted" style="margin: 0 0 16px">
      额度流水请到
      <router-link class="text-link" to="/billing">订单</router-link> 查看。
    </p>

    <p v-if="loadError" class="err">{{ loadError }}</p>
    <div v-else-if="!order.id" class="panel muted">正在读取订单…</div>
    <section v-else class="panel">
      <dl class="account-kv">
        <dt>订单号</dt>
        <dd class="order-id-line">
          <span>{{ order.id }}</span>
          <button class="btn btn-ghost" type="button" @click="copyId(order.id)">复制</button>
        </dd>
        <dt>类型</dt>
        <dd>{{ order.kind === 'CUSTOM' ? '灵活充值' : '套餐' }}</dd>
        <dt>项目</dt>
        <dd>{{ order.planName || '灵活充值' }}</dd>
        <dt>金额</dt>
        <dd>¥{{ yuan(order.amountCents) }}</dd>
        <dt>额度</dt>
        <dd>{{ order.quotaAmount ?? '—' }} 额度</dd>
        <dt>状态</dt>
        <dd>
          <span class="order-status" :class="order.status === 'PAID' ? 'ok' : 'wait'">{{ statusText(order.status) }}</span>
        </dd>
        <dt>创建时间</dt>
        <dd>{{ formatTime(order.createdAt) }}</dd>
        <dt>支付时间</dt>
        <dd>{{ order.status === 'PAID' ? formatTime(order.paidAt || order.createdAt) : '尚未支付' }}</dd>
      </dl>

      <div class="row" style="margin-top:18px">
        <button
          v-if="order.status === 'PENDING'"
          class="btn btn-accent"
          type="button"
          :disabled="paying"
          @click="payExisting"
        >{{ paying ? '正在入账…' : '继续支付' }}</button>
      </div>
    </section>

    <section v-if="order.id" class="panel" style="margin-top:16px">
      <p class="panel-label">关联流水</p>
      <h3>这张订单记入额度的记录</h3>
      <div v-if="!ledgers.length" class="empty-block">
        还没有关联流水。支付到账后会出现，也可到
        <router-link class="text-link" to="/billing">订单</router-link> 查看全部流水。
      </div>
      <div v-for="row in ledgers" :key="row.id" class="ledger-row">
        <div class="ledger-main">
        <p class="ledger-reason">{{ ledgerReason(row.reason) }}</p>
        <p class="page-ids ledger-ids">
          <CopyId v-if="row.id" label="流水 ID" :value="row.id" />
          <CopyId v-if="order.id" label="订单 ID" :value="order.id" />
        </p>
        <p class="muted ledger-meta">{{ formatTime(row.createdAt) }}</p>
        </div>
        <strong class="ledger-delta" :class="row.delta > 0 ? 'delta-plus' : 'delta-minus'">
          {{ row.delta > 0 ? '+' : '' }}{{ row.delta }} 额度
        </strong>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, asPage, qs } from '../api'
import { toast } from '../toast'
import { copyText, formatTime, ledgerReason, yuan } from '../labels'
import CopyId from '../components/CopyId.vue'

const props = defineProps({
  orderId: { type: [String, Number], default: '' },
  embedded: { type: Boolean, default: false }
})
const emit = defineEmits(['close', 'paid'])

const route = useRoute()
const refreshMe = inject('refreshMe', () => Promise.resolve())
const order = ref({})
const loadError = ref('')
const paying = ref(false)
const me = ref({})
const ledgers = computed(() => Array.isArray(order.value.ledger) ? order.value.ledger : [])
const resolvedId = computed(() => String(props.orderId || route.params.id || ''))

onMounted(load)
watch(resolvedId, load)

function statusText(status) {
  if (status === 'PAID') return '已到账'
  if (status === 'CANCELLED') return '已取消'
  return '待支付'
}

async function copyId(id) {
  const ok = await copyText(id)
  toast(ok ? '已复制订单号' : '复制失败，请手动选中', ok ? 'ok' : 'err')
}

async function load() {
  const id = resolvedId.value
  if (!id) return
  loadError.value = ''
  try {
    me.value = await api('/me')
    try {
      order.value = await api('/orders/' + id)
    } catch (e) {
      const list = asPage(await api('/orders' + qs({ size: 100 })))
      const found = list.items.find((o) => String(o.id) === String(id))
      if (!found) throw e
      order.value = found
    }
    await refreshMe()
  } catch (e) {
    order.value = {}
    loadError.value = e.message || '订单不存在'
  }
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

async function payExisting() {
  paying.value = true
  const before = Number(me.value.quota ?? 0)
  try {
    const paid = await api('/orders/' + order.value.id + '/mock-pay', { method: 'POST', body: {} })
    const settled = await waitPaid(order.value.id, paid)
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
