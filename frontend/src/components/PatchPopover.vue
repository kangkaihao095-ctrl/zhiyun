<template>
  <Teleport to="body">
    <div
      class="patch-pop"
      :class="{ sheet }"
      :style="sheet ? undefined : { top: top + 'px', left: left + 'px' }"
      role="dialog"
      aria-labelledby="patch-pop-title"
    >
      <p id="patch-pop-title" class="patch-pop-k">改稿对比</p>
      <PatchDiff :original="original" :proposed="proposed" />
      <p class="patch-pop-k">为什么这样改</p>
      <p class="patch-pop-why">{{ reason }}</p>
      <button class="btn btn-ghost patch-pop-close" type="button" @click="$emit('close')">关闭</button>
    </div>
  </Teleport>
</template>

<script setup>
import PatchDiff from './PatchDiff.vue'

defineProps({
  original: { type: String, default: '' },
  proposed: { type: String, default: '' },
  reason: { type: String, default: '' },
  top: { type: Number, default: 0 },
  left: { type: Number, default: 0 },
  sheet: { type: Boolean, default: false }
})

defineEmits(['close'])
</script>
