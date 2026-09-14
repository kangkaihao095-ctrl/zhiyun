<template>
  <div>
    <p class="page-kicker">记录</p>
    <h1 class="page-title">审校记录</h1>
    <p class="page-lead">这里是做过的检查。还可以继续处理还没确认的修改。可搜论文标题、检查方式和状态。</p>

    <ListPager
      v-model:q="q"
      v-model:page="page"
      v-model:size="size"
      :total="total"
      placeholder="论文标题、检查方式或状态"
      @search="search"
    />

    <div class="filter-row">
      <label class="zy-select-wrap">
        <span class="pager-size-label">状态</span>
        <select
          class="zy-select zy-select-compact"
          :value="filter"
          aria-label="按状态筛选"
          @change="setFilter($event.target.value)"
        >
          <option value="ALL">全部</option>
          <option value="ACTIVE">进行中</option>
          <option value="WAITING_ACCEPT">待确认</option>
          <option value="DONE">已完成</option>
          <option value="FAILED">未完成</option>
        </select>
      </label>
    </div>

    <div v-if="!rows.length" class="empty-block">还没有审校记录。从 <router-link class="text-link" to="/">我的论文</router-link> 上传并开始一次。</div>
    <div class="history-list">
      <article class="history-card" v-for="r in rows" :key="r.id">
        <div class="history-top">
          <strong>{{ r.manuscriptTitle }}</strong>
          <span v-if="r.unread" class="unread-pill">未读</span>
          <span class="status-pill" :data-s="r.status">{{ statusLabel(r.status) }}</span>
        </div>
        <p class="muted">{{ r.workflowName }} · {{ formatTime(r.createdAt) }}</p>
        <p class="page-ids">
          <CopyId label="任务 ID" :value="r.id" />
        </p>
        <p class="err" v-if="safeError(r)">{{ safeError(r) }}</p>
        <div class="row" style="margin-top:12px">
          <router-link class="btn btn-accent" :to="'/reviews/' + r.id">查看结果</router-link>
          <button
            v-if="r.status === 'PENDING' || r.status === 'RUNNING'"
            class="btn btn-ghost"
            type="button"
            :disabled="cancellingId === r.id"
            @click="cancelReview(r)"
          >{{ cancellingId === r.id ? '正在取消…' : '取消这次审校' }}</button>
          <button
            v-if="r.status === 'FAILED'"
            class="btn btn-ghost"
            type="button"
            :disabled="retryingId === r.id"
            @click="retryFromCheckpoint(r)"
          >{{ retryingId === r.id ? '正在继续…' : '从检查点继续' }}</button>
          <router-link class="btn btn-ghost" :to="'/manuscripts/' + r.manuscriptId">打开论文</router-link>
        </div>
        <p class="err" v-if="retryErrorId === r.id">{{ retryError }}</p>
        <p class="err" v-if="cancelErrorId === r.id">{{ cancelError }}</p>
      </article>
    </div>
  </div>
</template>

<script setup>
import { inject, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, asPage, DEFAULT_PAGE_SIZE, qs } from '../api'
import { formatTime, statusLabel } from '../labels'
import { publicErrorMessage } from '../public-error'
import { toast } from '../toast'
import ListPager from '../components/ListPager.vue'
import CopyId from '../components/CopyId.vue'

const router = useRouter()
const refreshMe = inject('refreshMe', () => Promise.resolve())
const rows = ref([])
const q = ref('')
const page = ref(1)
const size = ref(DEFAULT_PAGE_SIZE)
const total = ref(0)
const filter = ref('ALL')
const retryingId = ref(null)
const retryErrorId = ref(null)
const retryError = ref('')
const cancellingId = ref(null)
const cancelErrorId = ref(null)
const cancelError = ref('')

function safeError(row) {
  return publicErrorMessage(row?.errorMessage)
}

onMounted(async () => {
  try {
    await api('/inbox/read', { method: 'POST', body: { all: true } })
    await refreshMe()
  } catch {
    /* 角标刷新失败不挡列表 */
  }
  await load()
})
watch([page, size], () => {
  load()
})

function setFilter(next) {
  filter.value = next
  search()
}

function search() {
  page.value = 1
  load()
}

async function load() {
  const data = asPage(await api('/reviews' + qs({
    q: q.value,
    page: page.value,
    size: size.value,
    status: filter.value === 'ALL' ? '' : filter.value
  })))
  rows.value = data.items
  total.value = data.total
  page.value = data.page
  size.value = data.size
}

async function cancelReview(row) {
  cancelError.value = ''
  cancelErrorId.value = null
  cancellingId.value = row.id
  try {
    await api('/reviews/' + row.id + '/cancel', { method: 'POST' })
    toast('已取消这次审校')
    await load()
  } catch (e) {
    const msg = e.message || '没能取消'
    cancelError.value = msg
    cancelErrorId.value = row.id
    toast(msg, 'err')
  } finally {
    cancellingId.value = null
  }
}

async function retryFromCheckpoint(row) {
  retryError.value = ''
  retryErrorId.value = null
  retryingId.value = row.id
  try {
    await api('/reviews/' + row.id + '/retry', { method: 'POST' })
    toast('已从检查点继续。不另扣额度。')
    router.push('/reviews/' + row.id)
  } catch (e) {
    const msg = e.message || '没能从检查点继续'
    retryError.value = msg
    retryErrorId.value = row.id
    toast(msg, 'err')
  } finally {
    retryingId.value = null
  }
}
</script>
