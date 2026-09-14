<template>
  <div class="search-pager" :class="{ compact }" data-testid="search-pager">
    <div class="search-pager__row">
      <input
        class="search-pager__input"
        :value="q"
        :placeholder="placeholder"
        :data-testid="testId"
        @input="onType"
        @keyup.enter="searchNow"
      />
      <button class="search-pager__btn" type="button" @click="searchNow">搜索</button>
    </div>
    <div class="search-pager__nav" v-if="total > 0">
      <span class="search-pager__meta">{{ total }} 条 · 第 {{ page }} / {{ pages }} 页</span>
      <button type="button" class="search-pager__btn" :disabled="page <= 1" @click="go(page - 1)">上一页</button>
      <button type="button" class="search-pager__btn" :disabled="page >= pages" @click="go(page + 1)">下一页</button>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  q: { type: String, default: '' },
  page: { type: Number, default: 1 },
  size: { type: Number, default: 9 },
  total: { type: Number, default: 0 },
  placeholder: { type: String, default: '搜索' },
  compact: { type: Boolean, default: true },
  testId: { type: String, default: 'search-pager-q' }
})

const emit = defineEmits(['update:q', 'update:page', 'search'])

let debounce = 0
const pages = computed(() => Math.max(1, Math.ceil(Number(props.total || 0) / Number(props.size || 9))))

function onType(e) {
  emit('update:q', e.target.value)
  clearTimeout(debounce)
  debounce = window.setTimeout(() => emit('search'), 200)
}

function searchNow() {
  clearTimeout(debounce)
  emit('search')
}

function go(next) {
  emit('update:page', Math.min(pages.value, Math.max(1, next)))
}
</script>
