<template>
  <div v-if="show" class="priority-why">
    <p v-if="whyHigh" class="priority-why-line">
      <span>为什么高</span>{{ whyHigh }}
    </p>
    <p v-if="suggestFix" class="priority-why-line">
      <span>建议怎么改</span>{{ suggestFix }}
    </p>
    <p v-if="evidence.location" class="priority-why-line">
      <span>定位</span>{{ evidence.location }}
    </p>
    <p v-if="evidence.excerpt" class="priority-why-line">
      <span>原文</span>{{ evidence.excerpt }}
    </p>
    <p v-if="evidence.basis" class="priority-why-line">
      <span>规则依据</span>{{ evidence.basis }}
    </p>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  whyHigh: { type: String, default: '' },
  suggestFix: { type: String, default: '' },
  evidence: { type: Object, default: () => ({}) }
})

const evidence = computed(() => props.evidence || {})
const show = computed(() => Boolean(
  props.whyHigh
  || props.suggestFix
  || evidence.value.location
  || evidence.value.excerpt
  || evidence.value.basis
))
</script>
