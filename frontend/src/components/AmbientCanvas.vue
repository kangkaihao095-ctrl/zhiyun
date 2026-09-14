<template>
  <canvas ref="el" class="ambient-canvas" aria-hidden="true"></canvas>
</template>

<script setup>
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { theme } from '../theme'

const props = defineProps({
  intense: { type: Boolean, default: false }
})

const el = ref(null)
let raf = 0
let ctx = null
let w = 0
let h = 0
let running = false

function dark() {
  return theme.value === 'dark'
}

function blobs() {
  if (dark()) {
    if (props.intense) {
      return [
        { color: [198, 86, 32], a: 0.28, r: 0.46, sx: 0.18, sy: 0.16, px: 0.4, py: 1.1 },
        { color: [31, 51, 68], a: 0.34, r: 0.4, sx: 0.13, sy: 0.19, px: 1.7, py: 0.6 },
        { color: [92, 64, 36], a: 0.22, r: 0.34, sx: 0.21, sy: 0.14, px: 2.6, py: 2.1 },
        { color: [48, 40, 32], a: 0.4, r: 0.26, sx: 0.11, sy: 0.17, px: 0.9, py: 3.2 },
        { color: [36, 58, 52], a: 0.16, r: 0.3, sx: 0.15, sy: 0.12, px: 3.8, py: 1.4 }
      ]
    }
    return [
      { color: [198, 86, 32], a: 0.1, r: 0.38, sx: 0.07, sy: 0.06, px: 0.4, py: 1.1 },
      { color: [31, 51, 68], a: 0.14, r: 0.34, sx: 0.05, sy: 0.08, px: 1.7, py: 0.6 },
      { color: [92, 64, 36], a: 0.12, r: 0.28, sx: 0.08, sy: 0.05, px: 2.6, py: 2.1 },
      { color: [48, 40, 32], a: 0.22, r: 0.22, sx: 0.04, sy: 0.07, px: 0.9, py: 3.2 }
    ]
  }
  if (props.intense) {
    return [
      { color: [198, 86, 32], a: 0.42, r: 0.46, sx: 0.18, sy: 0.16, px: 0.4, py: 1.1 },
      { color: [31, 51, 68], a: 0.28, r: 0.4, sx: 0.13, sy: 0.19, px: 1.7, py: 0.6 },
      { color: [236, 196, 132], a: 0.36, r: 0.34, sx: 0.21, sy: 0.14, px: 2.6, py: 2.1 },
      { color: [255, 250, 240], a: 0.55, r: 0.26, sx: 0.11, sy: 0.17, px: 0.9, py: 3.2 },
      { color: [56, 92, 78], a: 0.16, r: 0.3, sx: 0.15, sy: 0.12, px: 3.8, py: 1.4 }
    ]
  }
  return [
    { color: [198, 86, 32], a: 0.16, r: 0.38, sx: 0.07, sy: 0.06, px: 0.4, py: 1.1 },
    { color: [31, 51, 68], a: 0.1, r: 0.34, sx: 0.05, sy: 0.08, px: 1.7, py: 0.6 },
    { color: [236, 196, 132], a: 0.14, r: 0.28, sx: 0.08, sy: 0.05, px: 2.6, py: 2.1 },
    { color: [255, 250, 240], a: 0.28, r: 0.22, sx: 0.04, sy: 0.07, px: 0.9, py: 3.2 }
  ]
}

function resize() {
  const canvas = el.value
  if (!canvas) return
  const dpr = Math.min(window.devicePixelRatio || 1, 1.75)
  w = window.innerWidth
  h = window.innerHeight
  canvas.width = Math.floor(w * dpr)
  canvas.height = Math.floor(h * dpr)
  canvas.style.width = w + 'px'
  canvas.style.height = h + 'px'
  ctx = canvas.getContext('2d')
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
}

function paint(now) {
  if (!ctx) return
  const t = now * 0.001
  ctx.clearRect(0, 0, w, h)
  ctx.fillStyle = dark() ? '#161310' : '#efe8dc'
  ctx.fillRect(0, 0, w, h)
  ctx.filter = `blur(${Math.max(72, Math.min(w, h) * 0.12)}px)`
  ctx.globalCompositeOperation = 'source-over'
  const list = blobs()
  list.forEach((b, i) => {
    const x = w * (0.28 + 0.5 * ((i % 3) / 2) + 0.16 * Math.sin(t * b.sx + b.px))
    const y = h * (0.22 + 0.48 * (i / list.length) + 0.14 * Math.cos(t * b.sy + b.py))
    const rad = Math.max(w, h) * b.r
    const g = ctx.createRadialGradient(x, y, rad * 0.05, x, y, rad)
    g.addColorStop(0, `rgba(${b.color.join(',')},${b.a})`)
    g.addColorStop(1, `rgba(${b.color.join(',')},0)`)
    ctx.fillStyle = g
    ctx.beginPath()
    ctx.arc(x, y, rad, 0, Math.PI * 2)
    ctx.fill()
  })
  ctx.globalCompositeOperation = dark() ? 'lighter' : 'screen'
  const hx = w * (0.62 + 0.08 * Math.sin(t * 0.12))
  const hy = h * (0.38 + 0.06 * Math.cos(t * 0.1))
  const hg = ctx.createRadialGradient(hx, hy, 0, hx, hy, Math.max(w, h) * 0.28)
  const glow = dark()
    ? (props.intense ? 'rgba(194,78,29,0.22)' : 'rgba(194,78,29,0.1)')
    : (props.intense ? 'rgba(255,248,236,0.55)' : 'rgba(255,248,236,0.22)')
  hg.addColorStop(0, glow)
  hg.addColorStop(1, dark() ? 'rgba(194,78,29,0)' : 'rgba(255,248,236,0)')
  ctx.fillStyle = hg
  ctx.beginPath()
  ctx.arc(hx, hy, Math.max(w, h) * 0.28, 0, Math.PI * 2)
  ctx.fill()
  ctx.filter = 'none'
  ctx.globalCompositeOperation = 'source-over'
}

function loop(now) {
  if (!running) return
  paint(now)
  raf = requestAnimationFrame(loop)
}

function start() {
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  resize()
  running = !reduce
  if (reduce) {
    paint(0)
    return
  }
  cancelAnimationFrame(raf)
  raf = requestAnimationFrame(loop)
}

onMounted(() => {
  start()
  window.addEventListener('resize', resize)
})

watch(() => props.intense, () => {
  if (!running) paint(0)
})
watch(theme, () => {
  if (!running) paint(0)
})

onUnmounted(() => {
  running = false
  cancelAnimationFrame(raf)
  window.removeEventListener('resize', resize)
})
</script>
