<template>
  <div class="list-tools">
    <div class="row list-tools-row">
      <input
        class="field"
        :value="q"
        :placeholder="placeholder"
        @input="onType"
        @keyup.enter="searchNow"
      />
      <button class="btn btn-ghost" type="button" @click="searchNow">搜索</button>
      <div class="pager-size zy-select-wrap">
        <span class="pager-size-label">每页</span>
        <select
          class="zy-select zy-select-compact"
          :value="size"
          aria-label="每页条数"
          @change="setSize(Number($event.target.value))"
        >
          <option v-for="n in sizes" :key="n" :value="n">{{ n }}</option>
        </select>
      </div>
    </div>
    <div class="row list-pager" v-if="total > 0">
      <span class="muted">共 {{ total }} 条</span>
      <button class="btn btn-ghost" type="button" :disabled="page <= 1" @click="go(page - 1)">上一页</button>
      <div class="pager-page" ref="pageBox">
        <button
          type="button"
          class="zy-select zy-select-compact zy-select-trigger pager-now"
          :aria-expanded="pageOpen"
          @click="pageOpen = !pageOpen"
        >第 {{ page }} / {{ pages }} 页</button>
        <div v-if="pageOpen" class="zy-select-menu pager-menu" role="listbox" aria-label="选择页码">
          <button
            v-for="n in pageButtons"
            :key="n"
            type="button"
            class="zy-select-option"
            :class="{ on: Number(page) === n }"
            role="option"
            :aria-selected="Number(page) === n"
            @click="pickPage(n)"
          >{{ n }}</button>
        </div>
      </div>
      <button class="btn btn-ghost" type="button" :disabled="page >= pages" @click="go(page + 1)">下一页</button>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'

const props = defineProps({
  q: { type: String, default: '' },
  page: { type: Number, default: 1 },
  size: { type: Number, default: 5 },
  total: { type: Number, default: 0 },
  placeholder: { type: String, default: '关键词，支持包含匹配' }
})

const emit = defineEmits(['update:q', 'update:page', 'update:size', 'search'])

const sizes = [5, 10, 50]
const pageOpen = ref(false)
const pageBox = ref(null)
let debounce = 0

const pages = computed(() => Math.max(1, Math.ceil(Number(props.total || 0) / Number(props.size || 5))))

const pageButtons = computed(() => {
  const total = pages.value
  if (total <= 12) return Array.from({ length: total }, (_, i) => i + 1)
  const cur = Number(props.page) || 1
  const set = new Set([1, total, cur - 1, cur, cur + 1, cur - 2, cur + 2])
  return [...set].filter((n) => n >= 1 && n <= total).sort((a, b) => a - b)
})

function onType(e) {
  const next = e.target.value
  emit('update:q', next)
  clearTimeout(debounce)
  debounce = window.setTimeout(() => emit('search'), next.trim() ? 280 : 0)
}

function searchNow() {
  clearTimeout(debounce)
  emit('search')
}

function go(next) {
  const n = Math.min(pages.value, Math.max(1, next))
  emit('update:page', n)
  pageOpen.value = false
}

function pickPage(n) {
  go(n)
}

function setSize(n) {
  emit('update:size', n)
  emit('update:page', 1)
}

function onDoc(e) {
  const t = e.target
  if (!(t instanceof Element)) return
  if (pageBox.value && !pageBox.value.contains(t)) pageOpen.value = false
}

onMounted(() => document.addEventListener('click', onDoc))
onUnmounted(() => {
  clearTimeout(debounce)
  document.removeEventListener('click', onDoc)
})
</script>
