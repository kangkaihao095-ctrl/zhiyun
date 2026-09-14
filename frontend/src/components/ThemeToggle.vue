<template>
  <button
    type="button"
    class="theme-toggle"
    data-testid="theme-toggle"
    :aria-pressed="theme === 'dark'"
    :aria-label="theme === 'dark' ? '切换为浅色' : '切换为深色'"
    :title="theme === 'dark' ? '当前深色，点击切换浅色' : '当前浅色，点击切换深色'"
    @click="onToggle"
  >
    <span class="theme-toggle-ico" aria-hidden="true">
      <svg v-if="theme === 'dark'" viewBox="0 0 24 24">
        <path d="M12 3a6.4 6.4 0 0 0 8.2 9.2A8 8 0 1 1 12 3z" />
      </svg>
      <svg v-else viewBox="0 0 24 24">
        <circle cx="12" cy="12" r="4" />
        <path d="M12 3v1.6M12 19.4V21M4.9 4.9l1.1 1.1M18 18l1.1 1.1M3 12h1.6M19.4 12H21M4.9 19.1 6 18M18 6l1.1-1.1" />
      </svg>
    </span>
    <span class="theme-toggle-label">{{ theme === 'dark' ? '深色' : '浅色' }}</span>
  </button>
</template>

<script setup>
import { onMounted } from 'vue'
import { bootTheme, theme, toggleTheme } from '../theme'

onMounted(() => {
  bootTheme()
})

function onToggle() {
  toggleTheme()
}
</script>
