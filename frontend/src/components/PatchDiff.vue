<template>
  <p
    v-if="!rows.length"
    class="patch-diff-empty muted"
    data-testid="patch-diff-empty"
  >本条无需改稿对比</p>
  <div
    v-else
    class="merge-diff patch-diff"
    role="region"
    aria-label="改稿对比"
    data-testid="patch-diff"
  >
    <template v-for="(row, i) in rows" :key="i">
      <div v-if="row.type === 'skip'" class="diff-skip">··· 未改 {{ row.count }} 行</div>
      <div v-else class="diff-line" :class="row.type" :data-diff="row.type">
        <span class="diff-mark">{{ row.type === 'del' ? '−' : row.type === 'add' ? '+' : ' ' }}</span>
        <span class="diff-text">{{ row.text || ' ' }}</span>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { patchDiffRows } from '../merge-diff'

const props = defineProps({
  original: { type: String, default: '' },
  proposed: { type: String, default: '' }
})

const rows = computed(() => patchDiffRows(props.original, props.proposed))
</script>
