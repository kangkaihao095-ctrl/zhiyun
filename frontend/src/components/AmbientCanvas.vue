<template>
  <div class="ambient" :class="{ intense }" aria-hidden="true">
    <span class="ambient-orb a" />
    <span class="ambient-orb b" />
    <span class="ambient-orb c" />
    <span class="ambient-grid" />
    <span class="ambient-vignette" />
  </div>
</template>

<script setup>
defineProps({
  intense: { type: Boolean, default: false }
})
</script>

<style scoped>
.ambient {
  position: fixed;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
  background: var(--bg);
}
.ambient-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  will-change: transform;
}
.ambient-orb.a {
  width: 46vw;
  height: 46vw;
  top: -18vw;
  left: -8vw;
  background: radial-gradient(circle, var(--ambient-a), transparent 68%);
  animation: zy-drift-a 26s var(--ease-in-out) infinite;
}
.ambient-orb.b {
  width: 40vw;
  height: 40vw;
  top: -12vw;
  right: -10vw;
  background: radial-gradient(circle, var(--ambient-b), transparent 68%);
  animation: zy-drift-b 32s var(--ease-in-out) infinite;
}
.ambient-orb.c {
  width: 50vw;
  height: 50vw;
  bottom: -30vw;
  left: 30vw;
  background: radial-gradient(circle, var(--ambient-c), transparent 70%);
  animation: zy-drift-a 38s var(--ease-in-out) infinite reverse;
}
.ambient-grid {
  position: absolute;
  inset: 0;
  background-image: radial-gradient(var(--ambient-dot) 1px, transparent 1px);
  background-size: 22px 22px;
  -webkit-mask-image: radial-gradient(ellipse 80% 60% at 50% 0%, #000 20%, transparent 75%);
  mask-image: radial-gradient(ellipse 80% 60% at 50% 0%, #000 20%, transparent 75%);
}
.ambient-vignette {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, transparent 60%, var(--bg) 100%);
}
.intense .ambient-orb { filter: blur(70px); }
.intense .ambient-orb.a { width: 60vw; height: 60vw; opacity: 1.4; }
.intense .ambient-grid {
  -webkit-mask-image: radial-gradient(ellipse 70% 70% at 30% 40%, #000 10%, transparent 75%);
  mask-image: radial-gradient(ellipse 70% 70% at 30% 40%, #000 10%, transparent 75%);
}
</style>
