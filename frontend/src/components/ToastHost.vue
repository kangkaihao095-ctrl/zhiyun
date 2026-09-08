<template>
  <div class="toasts" aria-live="polite">
    <div v-for="t in toasts" :key="t.id" class="toast" :class="t.type">
      {{ t.message }}
    </div>
  </div>
</template>

<script setup>
import { toasts } from '../toast'
</script>
