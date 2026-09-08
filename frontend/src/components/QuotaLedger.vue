<template>
  <section id="ledger" class="panel">
    <p class="panel-label">额度流水</p>
    <h3>充值和消耗记录</h3>
    <p class="muted" style="margin: 0 0 12px">按时间倒序。可搜原因、任务/订单号或额度数字。消耗在审校结束后结算。</p>
    <ListPager
      v-model:q="q"
      v-model:page="page"
      v-model:size="size"
      :total="total"
      placeholder="原因、任务号、订单号或额度"
      @search="search"
    />
    <div v-if="!rows.length" class="empty-block">还没有账变。充值或完成一次审校后会出现。</div>
    <div v-for="row in rows" :key="row.id" class="ledger-row">
      <div class="ledger-main">
        <p class="ledger-reason">{{ row.reasonLabel }}</p>
        <p class="page-ids ledger-ids">
          <CopyId v-if="row.ids.ledgerId" label="流水 ID" :value="row.ids.ledgerId" />
          <CopyId v-if="row.ids.orderId" label="订单 ID" :value="row.ids.orderId" />
          <CopyId v-if="row.ids.taskId" label="任务 ID" :value="row.ids.taskId" />
        </p>
        <p class="muted ledger-meta">
          {{ formatTime(row.createdAt) }}
          <template v-if="row.related.label">
            ·
            <router-link v-if="row.related.to" class="text-link" :to="row.related.to">{{ row.related.label }}</router-link>
            <span v-else>{{ row.related.label }}</span>
          </template>
        </p>
      </div>
      <strong class="ledger-delta" :class="row.delta > 0 ? 'delta-plus' : 'delta-minus'">
        {{ row.delta > 0 ? '+' : '' }}{{ row.delta }} 额度
      </strong>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api, asPage, DEFAULT_PAGE_SIZE, qs } from '../api'
import { formatTime, ledgerReason, ledgerRelated, ledgerIds } from '../labels'
import { toast } from '../toast'
import ListPager from './ListPager.vue'
import CopyId from './CopyId.vue'

const props = defineProps({
  refreshToken: { type: Number, default: 0 }
})

const ledger = ref([])
const q = ref('')
const page = ref(1)
const size = ref(DEFAULT_PAGE_SIZE)
const total = ref(0)

async function load() {
  try {
    const data = asPage(await api('/ledger' + qs({ q: q.value, page: page.value, size: size.value })))
    ledger.value = data.items
    total.value = data.total
    page.value = data.page
    size.value = data.size
  } catch (e) {
    toast(e.message || '流水加载失败', 'err')
  }
}

function search() {
  page.value = 1
  load()
}

onMounted(load)
watch(() => props.refreshToken, load)
watch([page, size], load)

const rows = computed(() => ledger.value.map((row) => ({
  ...row,
  reasonLabel: ledgerReason(row.reason),
  related: ledgerRelated(row.refId),
  ids: ledgerIds(row)
})))
</script>
