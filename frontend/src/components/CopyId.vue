<template>
  <span v-if="text" class="copy-id" @click.stop>
    <span class="copy-id-k">{{ label }}</span>
    <code class="copy-id-v">{{ text }}</code>
    <button class="copy-id-btn" type="button" @click.stop="copy">复制</button>
  </span>
</template>

<script setup>
import { computed } from 'vue'
import { copyText } from '../labels'
import { toast } from '../toast'

const props = defineProps({
  label: { type: String, default: 'ID' },
  value: { type: [String, Number], default: '' }
})

const text = computed(() => {
  if (props.value == null || props.value === '') return ''
  return String(props.value)
})

async function copy() {
  const ok = await copyText(text.value)
  toast(ok ? `已复制${props.label}` : '复制失败，请手动选中', ok ? 'ok' : 'err')
}
</script>
