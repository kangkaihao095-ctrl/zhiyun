<script setup>
import { computed, nextTick, ref } from 'vue'
import { axisTickLines, toolAxisLabel } from '../observability.js'

const props = defineProps({
  labels: { type: Array, default: () => [] },
  values: { type: Array, default: () => [] },
  color: { type: String, default: 'var(--ops-blue)' },
  format: { type: Function, default: (n) => String(n) },
  height: { type: Number, default: 200 },
  layout: { type: String, default: 'vertical' },
  labelMax: { type: Number, default: 12 },
  fill: { type: Boolean, default: false }
})

const hoverIdx = ref(null)
const tipEl = ref(null)
const tip = ref(null)

function shortLabel(s, max = props.labelMax) {
  const text = toolAxisLabel(s) || String(s || '')
  if (text.length <= max) return text
  return `${text.slice(0, Math.max(1, max - 1))}…`
}

const chart = computed(() => {
  const isH = props.layout === 'horizontal'
  const n = Math.max(props.labels.length, 1)
  const values = props.values.length ? props.values.map((v) => Number(v) || 0) : [0]
  const yMax = Math.max(...values, 1) * 1.12

  if (isH) {
    const W = 560
    const padT = 6
    const padB = 18
    const padL = 108
    const padR = 44
    const avail = Math.max(props.height - padT - padB, n * 18)
    const rowH = avail / n
    const barH = Math.min(14, Math.max(8, rowH - 6))
    const H = props.height
    const iw = W - padL - padR
    const rows = props.labels.map((label, i) => {
      const v = Number(props.values[i] || 0)
      const w = (v / yMax) * iw
      const y = padT + i * rowH + (rowH - barH) / 2
      return {
        label,
        short: shortLabel(label),
        v,
        x: padL,
        y,
        w: Math.max(w, v > 0 ? 2 : 0),
        h: barH,
        midY: y + barH / 2
      }
    })
    const ticks = [0, 0.5, 1].map((t) => ({
      x: padL + iw * t,
      label: props.format(Math.round(yMax * t * 10) / 10)
    }))
    return { kind: 'h', W, H, padL, padR, padT, iw, ticks, rows, yMax }
  }

  const W = 560
  const H = props.height
  const padL = 52
  const padR = 16
  const padT = 26
  const iw = W - padL - padR
  const slot = iw / n
  const many = n > 6
  const longName = props.labels.some((label) => toolAxisLabel(label).length > 6)
  const dense = many
  const tight = many || longName
  const fontPx = tight ? 9 : 10
  const cap = many ? 4 : Math.min(props.labelMax, 8)
  const maxPerLine = Math.max(2, Math.min(cap, Math.floor(slot / (fontPx * 0.62))))
  const ticksMeta = props.labels.map((label) => ({
    label,
    lines: axisTickLines(label, { maxPerLine, dense })
  }))
  const wrapped = ticksMeta.some((t) => t.lines.length > 1)
  const padB = wrapped ? 58 : tight ? 48 : 40
  const ih = H - padT - padB
  const gap = Math.min(12, (iw / n) * 0.2)
  const bw = Math.min(36, (iw / n) - gap)
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((t) => ({
    y: padT + ih - ih * t,
    label: props.format(Math.round(yMax * t * 10) / 10)
  }))
  const labelY = H - padB + 16
  const lineGap = tight ? 11 : 12
  const bars = props.labels.map((label, i) => {
    const v = Number(props.values[i] || 0)
    const x = padL + slot * i + (slot - bw) / 2
    const h = (v / yMax) * ih
    const y = padT + ih - h
    const lines = ticksMeta[i].lines
    return {
      label,
      short: lines.join(''),
      lines,
      v,
      x,
      y,
      h: Math.max(h, v > 0 ? 2 : 0),
      cx: x + bw / 2,
      topY: y,
      labelY
    }
  })
  return {
    kind: 'v',
    W,
    H,
    padL,
    padT,
    padB,
    iw,
    ih,
    bw,
    ticks,
    bars,
    tight,
    labelY,
    lineGap,
    yMax
  }
})

function placeTip(el, ev, tw, th) {
  if (!el) return null
  const wrap = el.getBoundingClientRect()
  let left = ev.clientX - wrap.left + 12
  let top = ev.clientY - wrap.top - th - 10
  if (left + tw > wrap.width - 8) left = wrap.width - tw - 8
  if (left < 8) left = 8
  if (top < 8) top = ev.clientY - wrap.top + 16
  return { left, top }
}

function syncTip(i, ev, wrap) {
  const el = wrap || ev.currentTarget?.closest?.('.chart-wrap')
  const pos = placeTip(el, ev, tipEl.value?.offsetWidth || 140, tipEl.value?.offsetHeight || 56)
  if (!pos) return
  tip.value = {
    ...pos,
    label: props.labels[i] ?? '',
    value: props.format(Number(props.values[i] || 0))
  }
}

function onEnter(i, ev) {
  const wrap = ev.currentTarget.closest('.chart-wrap')
  hoverIdx.value = i
  syncTip(i, ev, wrap)
  nextTick(() => syncTip(i, ev, wrap))
}

function onMove(ev) {
  if (hoverIdx.value == null || !tip.value) return
  syncTip(hoverIdx.value, ev)
}

function onLeave() {
  hoverIdx.value = null
  tip.value = null
}
</script>

<template>
  <div class="chart-wrap" :class="{ fill }" @mousemove="onMove">
    <svg
      v-if="labels.length && chart.kind === 'v'"
      :viewBox="`0 0 ${chart.W} ${chart.H}`"
      overflow="hidden"
      role="img"
      class="chart"
    >
      <line
        v-for="(t, i) in chart.ticks"
        :key="'g' + i"
        :x1="chart.padL"
        :x2="chart.padL + chart.iw"
        :y1="t.y"
        :y2="t.y"
        class="grid"
      />
      <text
        v-for="(t, i) in chart.ticks"
        :key="'yl' + i"
        :x="chart.padL - 8"
        :y="t.y + 3"
        text-anchor="end"
        class="tick"
      >{{ t.label }}</text>

      <g
        v-for="(b, i) in chart.bars"
        :key="'b' + i"
        class="bar-g"
        :class="{ dim: hoverIdx !== null && hoverIdx !== i, active: hoverIdx === i }"
        @mouseenter="onEnter(i, $event)"
        @mouseleave="onLeave"
      >
        <rect
          :x="b.x - 4"
          :y="chart.padT"
          :width="chart.bw + 8"
          :height="chart.ih"
          fill="transparent"
        />
        <rect
          class="bar"
          :x="b.x"
          :y="b.y"
          :width="chart.bw"
          :height="b.h"
          :fill="color"
          rx="4"
          :style="{ '--bar-delay': `${i * 40}ms` }"
        />
        <text
          :x="b.cx"
          :y="b.topY - 6"
          text-anchor="middle"
          class="val-label"
        >{{ format(b.v) }}</text>
      </g>

      <g v-for="(b, i) in chart.bars" :key="'xl' + i">
        <text
          :x="b.cx"
          :y="chart.labelY"
          text-anchor="middle"
          class="tick x-tick"
          :class="{ tight: chart.tight }"
        >
          <title>{{ b.label }}</title>
          <tspan
            v-for="(line, li) in b.lines"
            :key="li"
            :x="b.cx"
            :dy="li === 0 ? 0 : chart.lineGap"
          >{{ line }}</tspan>
        </text>
      </g>
    </svg>

    <svg
      v-else-if="labels.length && chart.kind === 'h'"
      :viewBox="`0 0 ${chart.W} ${chart.H}`"
      overflow="hidden"
      role="img"
      class="chart"
    >
      <line
        v-for="(t, i) in chart.ticks"
        :key="'vg' + i"
        :x1="t.x"
        :x2="t.x"
        :y1="chart.padT"
        :y2="chart.H - 8"
        class="grid"
      />
      <text
        v-for="(t, i) in chart.ticks"
        :key="'xt' + i"
        :x="t.x"
        :y="chart.H - 2"
        text-anchor="middle"
        class="tick"
      >{{ t.label }}</text>

      <g
        v-for="(r, i) in chart.rows"
        :key="'r' + i"
        class="bar-g"
        :class="{ dim: hoverIdx !== null && hoverIdx !== i, active: hoverIdx === i }"
        @mouseenter="onEnter(i, $event)"
        @mouseleave="onLeave"
      >
        <rect
          :x="0"
          :y="r.y - 4"
          :width="chart.W"
          :height="r.h + 8"
          fill="transparent"
        />
        <text
          :x="chart.padL - 8"
          :y="r.midY + 4"
          text-anchor="end"
          class="tick y-cat"
        >
          <title>{{ r.label }}</title>
          {{ r.short }}
        </text>
        <rect
          class="bar bar--h"
          :x="r.x"
          :y="r.y"
          :width="r.w"
          :height="r.h"
          :fill="color"
          rx="4"
          :style="{ '--bar-delay': `${i * 35}ms` }"
        />
        <text
          :x="r.x + r.w + 6"
          :y="r.midY + 4"
          class="val-label"
        >{{ format(r.v) }}</text>
      </g>
    </svg>

    <div v-else class="empty">暂无数据</div>

    <Transition name="tip">
      <div
        v-if="tip"
        ref="tipEl"
        class="tooltip"
        :style="{ left: `${tip.left}px`, top: `${tip.top}px` }"
      >
        <div class="tooltip__label">{{ tip.label }}</div>
        <div class="tooltip__value">{{ tip.value }}</div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.chart-wrap {
  position: relative;
  width: 100%;
  overflow: hidden;
}
.chart-wrap.fill {
  flex: 1 1 auto;
  height: 100%;
  min-height: 160px;
  display: flex;
  flex-direction: column;
}
.chart-wrap.fill .chart {
  flex: 1 1 auto;
  height: 100%;
  min-height: 160px;
}
.chart {
  display: block;
  width: 100%;
  height: auto;
  overflow: hidden;
}
.grid {
  stroke: var(--color-border-subtle, var(--ops-gridline));
  stroke-width: 1;
  stroke-dasharray: 3 3;
}
.tick {
  fill: var(--color-text-secondary, var(--ops-muted));
  font-size: 10.5px;
  font-variant-numeric: tabular-nums;
}
.y-cat { font-size: 10px; }
.x-tick {
  font-size: 10px;
  writing-mode: horizontal-tb;
  transform: none;
}
.x-tick.tight { font-size: 9px; }
.bar-g {
  cursor: pointer;
  transition: opacity 0.18s ease;
}
.bar-g.dim { opacity: 0.35; }
.bar {
  transform-origin: bottom;
  transform-box: fill-box;
  animation: bar-in-y 0.55s cubic-bezier(0.22, 1, 0.36, 1) both;
  animation-delay: var(--bar-delay, 0ms);
  transition: filter 0.15s ease, opacity 0.15s ease;
}
.bar--h {
  transform-origin: left center;
  animation-name: bar-in-x;
}
.bar-g.active .bar { filter: brightness(1.08); }
.val-label {
  fill: var(--color-text-primary, var(--ops-title));
  font-size: 10px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  pointer-events: none;
}
@keyframes bar-in-y {
  from { transform: scaleY(0); opacity: 0.4; }
  to { transform: scaleY(1); opacity: 1; }
}
@keyframes bar-in-x {
  from { transform: scaleX(0); opacity: 0.4; }
  to { transform: scaleX(1); opacity: 1; }
}
.tooltip {
  position: absolute;
  z-index: 8;
  pointer-events: none;
  min-width: 88px;
  max-width: 240px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #1f2329;
  color: #fff;
  white-space: nowrap;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.18);
}
.tooltip__label {
  font-size: 11px;
  opacity: 0.78;
  line-height: 1.35;
  margin-bottom: 2px;
}
.tooltip__value {
  font-size: 14px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.tip-enter-active,
.tip-leave-active { transition: opacity 0.12s ease; }
.tip-enter-from,
.tip-leave-to { opacity: 0; }
.empty {
  min-height: 140px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--ops-empty);
  font-size: 12px;
  background: var(--ops-panel-2);
  border: 1px dashed var(--ops-hover-border);
  border-radius: 6px;
}
</style>
