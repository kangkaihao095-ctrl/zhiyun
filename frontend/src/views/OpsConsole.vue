<template>
  <div class="ops-console" data-testid="ops-console">
    <Observability v-if="allowed" />
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import Observability from './Observability.vue'

const router = useRouter()
const allowed = ref(false)

onMounted(async () => {
  try {
    const me = await api('/me')
    if (!me?.ops) {
      await router.replace('/ops/login')
      return
    }
    allowed.value = true
  } catch {
    await router.replace('/ops/login')
  }
})
</script>
