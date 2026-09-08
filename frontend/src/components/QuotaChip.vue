<template>
  <button type="button" class="quota-chip" :class="{ compact, large }" @click="$emit('click')">
    <span class="quota-chip-glow" aria-hidden="true" />
    <span class="quota-chip-num">{{ quota ?? 0 }}</span>
    <span class="quota-chip-unit">{{ large ? '剩余额度' : '额度' }}</span>
    <span class="quota-tip" role="tooltip">
      <strong>剩余 {{ quotaText(quota) }}</strong>
      <em>{{ quotaHint(quota) }}</em>
      <em v-if="!large">打开额度流水。充值请点旁边按钮。</em>
    </span>
  </button>
</template>

<script setup>
import { quotaHint, quotaText } from '../labels'

defineProps({
  quota: { type: [Number, String], default: 0 },
  compact: { type: Boolean, default: false },
  large: { type: Boolean, default: false }
})

defineEmits(['click'])
</script>
