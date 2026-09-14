<template>
  <div class="row list-pager" v-if="total > size">
    <span class="muted">共 {{ total }} 条</span>
    <button class="btn btn-ghost" type="button" :disabled="page <= 1" @click="go(page - 1)">上一页</button>
    <span class="muted">第 {{ page }} / {{ pages }} 页</span>
    <button class="btn btn-ghost" type="button" :disabled="page >= pages" @click="go(page + 1)">下一页</button>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  page: { type: Number, default: 1 },
  size: { type: Number, default: 5 },
  total: { type: Number, default: 0 }
})
const emit = defineEmits(['update:page'])

const pages = computed(() => Math.max(1, Math.ceil(Number(props.total || 0) / Number(props.size || 5))))

function go(next) {
  emit('update:page', Math.min(pages.value, Math.max(1, next)))
}
</script>
