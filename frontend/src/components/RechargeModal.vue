<template>
  <Teleport to="body">
    <div v-if="open" class="overlay recharge-overlay" @click.self="close">
      <div class="modal recharge-modal" role="dialog" aria-modal="true" aria-labelledby="recharge-title">
        <template v-if="!pending">
          <h2 id="recharge-title">充值</h2>
          <p class="muted">选套餐或按金额充。确认后马上到账。最低 10 元，1 元到账 1 额度。</p>

          <section class="recharge-custom">
            <p class="panel-label">灵活充值</p>
            <label class="custom-input-wrap">
              <span>¥</span>
              <input
                class="custom-input"
                type="number"
                min="10"
                step="1"
                v-model.number="customYuan"
                @focus="onCustomFocus"
              />
            </label>
            <p class="custom-preview">到账 <strong>{{ customQuota }}</strong> 额度</p>
            <div class="quick-amt">
              <button
                v-for="n in quickYuan"
                :key="n"
                type="button"
                class="fmt-pill"
                :class="{ on: selectedPlanId === null && customYuan === n }"
                @click="pickCustom(n)"
              >¥{{ n }}</button>
            </div>
            <p class="err" v-if="customTouched && customYuan < 10">最低充值 10 元</p>
            <button class="btn btn-accent" type="button" :disabled="customYuan < 10 || paying" @click="openCustom">
              充值 {{ customQuota }} 额度
            </button>
          </section>

          <p class="panel-label recharge-plans-label">套餐</p>
          <div class="price-grid recharge-plans">
            <div
              class="price-card"
              :class="['plan-' + (p.code || 'default'), { on: selectedPlanId === p.id }]"
              v-for="p in plans"
              :key="p.id"
              role="button"
              tabindex="0"
              @click="selectPlan(p)"
              @keydown.enter.prevent="selectPlan(p)"
            >
              <span v-if="p.code === 'pro'" class="price-badge">推荐</span>
              <p class="price-name">{{ p.name }}</p>
              <p class="price-amt">¥{{ yuan(p.priceCents) }}</p>
              <p>{{ p.quotaAmount }} 额度 · 比灵活充值多 {{ extra(p) }} 额度</p>
              <p>{{ planDesc(p.description) }}</p>
              <button
                class="btn"
                :class="selectedPlanId === p.id ? 'btn-accent' : ''"
                type="button"
                @click.stop="selectedPlanId === p.id ? openBuy(p) : selectPlan(p)"
              >
                {{ selectedPlanId === p.id ? '去支付' : '选择' }}
              </button>
            </div>
          </div>
        </template>

        <template v-else>
          <h2 id="recharge-title">确认{{ pending.kind === 'custom' ? '充值' : '购买' }}</h2>
          <p class="muted">确认后生成订单，额度马上到账。</p>
          <dl class="modal-kv">
            <dt>项目</dt><dd>{{ pending.name }} · {{ pending.quotaAmount }} 额度</dd>
            <dt>应付</dt><dd>¥{{ yuan(pending.priceCents) }}</dd>
            <dt>当前额度</dt><dd>{{ beforeQuota }} 额度</dd>
            <dt>到账后</dt><dd>{{ beforeQuota + pending.quotaAmount }} 额度</dd>
          </dl>
          <p class="err" v-if="buyError">{{ buyError }}</p>
          <div class="modal-actions">
            <button class="btn btn-ghost" type="button" :disabled="paying" @click="backToPick">再想想</button>
            <button class="btn btn-accent" type="button" :disabled="paying" @click="confirmBuy">
              {{ paying ? '正在入账…' : '确认并到账' }}
            </button>
          </div>
        </template>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, inject, onMounted, onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import { toast } from '../toast'
import { yuan } from '../labels'

const props = defineProps({
  open: { type: Boolean, default: false }
})
const emit = defineEmits(['update:open', 'paid'])

const refreshMe = inject('refreshMe', () => Promise.resolve())
const me = ref({})
const plans = ref([])
const pending = ref(null)
const paying = ref(false)
const buyError = ref('')
const customYuan = ref(10)
const customTouched = ref(false)
const selectedPlanId = ref(null)
const quickYuan = [10, 20, 50, 100, 200]
const customQuota = computed(() => Math.max(0, Math.floor(Number(customYuan.value) || 0)))
const beforeQuota = computed(() => Number(me.value.quota ?? 0))

onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => window.removeEventListener('keydown', onKey))

watch(() => props.open, (on) => {
  if (on) load()
  else reset()
})

function onKey(e) {
  if (e.key !== 'Escape' || !props.open) return
  close()
}

function extra(plan) {
  const yuanAmt = Math.round((plan.priceCents || 0) / 100)
  return Math.max(0, (plan.quotaAmount || 0) - yuanAmt)
}

function planDesc(text) {
  return String(text || '').replace(/(\d+)\s*点/g, '$1 额度')
}

function selectPlan(plan) {
  selectedPlanId.value = plan.id
}

function onCustomFocus() {
  customTouched.value = true
  selectedPlanId.value = null
}

function pickCustom(n) {
  customYuan.value = n
  selectedPlanId.value = null
}

async function load() {
  me.value = await api('/me')
  plans.value = await api('/plans')
  await refreshMe()
}

function openBuy(plan) {
  buyError.value = ''
  selectedPlanId.value = plan.id
  pending.value = { ...plan, kind: 'package' }
}

function openCustom() {
  if (customQuota.value < 10) {
    customTouched.value = true
    return
  }
  selectedPlanId.value = null
  buyError.value = ''
  pending.value = {
    kind: 'custom',
    name: '灵活充值',
    quotaAmount: customQuota.value,
    priceCents: customQuota.value * 100
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

function reset() {
  if (paying.value) return
  pending.value = null
  buyError.value = ''
  selectedPlanId.value = null
  customTouched.value = false
}

function close() {
  if (paying.value) return
  reset()
  emit('update:open', false)
}

function backToPick() {
  if (paying.value) return
  pending.value = null
  buyError.value = ''
}

async function confirmBuy() {
  const item = pending.value
  if (!item) return
  paying.value = true
  buyError.value = ''
  const before = beforeQuota.value
  let order = null
  try {
    const body = item.kind === 'custom' ? { amountYuan: item.quotaAmount } : { planId: item.id }
    order = await api('/orders', { method: 'POST', body })
    const paid = await api('/orders/' + order.id + '/mock-pay', { method: 'POST', body: {} })
    const settled = await waitPaid(order.id, paid)
    await load()
    const after = Number(me.value.quota ?? 0)
    if (settled.status !== 'PAID' || after < before + item.quotaAmount) {
      toast('订单已生成，演示渠道正在入账。请到订单页查看流水。', 'err')
    } else {
      pending.value = null
      emit('update:open', false)
      emit('paid')
      toast(`已到账。${item.name} +${item.quotaAmount} 额度：${before} → ${after}。`)
    }
  } catch (e) {
    await load().catch(() => {})
    const msg = e.message || '请稍后重试'
    if (order) {
      buyError.value = '订单已生成，支付还没成功。可到订单页继续。'
      toast(msg, 'err')
    } else {
      buyError.value = msg
      toast(msg, 'err')
    }
  } finally {
    paying.value = false
  }
}
</script>
