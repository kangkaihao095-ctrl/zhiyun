<script setup>
import { computed, ref } from 'vue'
import { sparseAxisTick } from '../observability.js'

const props = defineProps({
  labels: { type: Array, default: () => [] },
  startedValues: { type: Array, default: () => [] },
  succeededValues: { type: Array, default: () => [] },
  failedValues: { type: Array, default: () => [] },
  rateValues: { type: Array, default: () => [] },
  height: { type: Number, default: 240 },
  fill: { type: Boolean, default: false },
  format: { type: Function, default: (n) => String(n) }
})

const hoverIdx = ref(null)

const chart = computed(() => {
  const W = 760
  const H = props.height
  const padL = 48
  const padR = 46
  const padT = 28
  const padB = 40
  const iw = W - padL - padR
  const ih = H - padT - padB
  const n = Math.max(props.labels.length, 1)
  const countMax = Math.max(1, ...props.startedValues, ...props.succeededValues, ...props.failedValues) * 1.22
  const slot = iw / n
  const barW = Math.min(22, slot * 0.22)

  const countTicks = [0, 0.25, 0.5, 0.75, 1].map((ratio) => ({
    y: padT + ih * (1 - ratio),
    label: props.format(Math.round(countMax * ratio))
  }))
  const rateTicks = [0, 25, 50, 75, 100].map((value) => ({
    y: padT + ih * (1 - value / 100),
    label: `${value}%`
  }))
  const groups = props.labels.map((label, index) => {
    const cx = padL + slot * index + slot / 2
    const started = Number(props.startedValues[index] || 0)
    const succeeded = Number(props.succeededValues[index] || 0)
    const failed = Number(props.failedValues[index] || 0)
    const rate = Number(props.rateValues[index] || 0)
    const startedH = (started / countMax) * ih
    const succeededH = (succeeded / countMax) * ih
    return {
      label,
      started,
      succeeded,
      failed,
      rate,
      cx,
      startedX: cx - barW - 2,
      succeededX: cx + 2,
      startedY: padT + ih - startedH,
      succeededY: padT + ih - succeededH,
      startedH,
      succeededH,
      rateY: padT + ih * (1 - Math.min(Math.max(rate, 0), 100) / 100)
    }
  })
  const rateLine = groups
    .map((group, index) => `${index === 0 ? 'M' : 'L'} ${group.cx} ${group.rateY}`)
    .join(' ')

  return {
    W, H, padL, padR, padT, padB, iw, ih, barW,
    countTicks, rateTicks, groups, rateLine
  }
})

const tipBox = computed(() => {
  if (hoverIdx.value == null) return null
  const g = chart.value.groups[hoverIdx.value]
  if (!g) return null
  const { W, H } = chart.value
  const tw = 168
  const th = 70
  let x = g.cx + 10
  if (x + tw > W - 8) x = g.cx - tw - 10
  if (x < 8) x = 8
  let y = g.rateY - th - 6
  if (y < 6) y = g.rateY + 12
  if (y + th > H - 6) y = Math.max(6, H - th - 6)
  return { x, y, tw, th, g }
})
</script>

<template>
  <div v-if="labels.length" class="combo-chart" :class="{ fill }">
    <div class="legend">
      <span><i class="legend__bar legend__bar--started" />创建</span>
      <span><i class="legend__bar legend__bar--ok" />完成</span>
      <span><i class="legend__line" />完成率</span>
    </div>
    <svg :viewBox="`0 0 ${chart.W} ${chart.H}`" class="chart" role="img" aria-label="任务创建、完成与完成率">
      <line
        v-for="(tick, index) in chart.countTicks"
        :key="'grid-' + index"
        :x1="chart.padL"
        :x2="chart.padL + chart.iw"
        :y1="tick.y"
        :y2="tick.y"
        class="grid"
      />
      <text
        v-for="(tick, index) in chart.countTicks"
        :key="'left-' + index"
        :x="chart.padL - 8"
        :y="tick.y + 4"
        text-anchor="end"
        class="tick"
      >{{ tick.label }}</text>
      <text
        v-for="(tick, index) in chart.rateTicks"
        :key="'right-' + index"
        :x="chart.W - chart.padR + 8"
        :y="tick.y + 4"
        text-anchor="start"
        class="tick"
      >{{ tick.label }}</text>

      <g
        v-for="(group, index) in chart.groups"
        :key="group.label + index"
        class="group"
        :class="{ dim: hoverIdx !== null && hoverIdx !== index }"
        @mouseenter="hoverIdx = index"
        @mouseleave="hoverIdx = null"
      >
        <rect
          :x="group.cx - chart.barW - 8"
          :y="chart.padT"
          :width="chart.barW * 2 + 16"
          :height="chart.ih"
          fill="transparent"
        />
        <rect
          :x="group.startedX"
          :y="group.startedY"
          :width="chart.barW"
          :height="Math.max(group.startedH, group.started > 0 ? 2 : 0)"
          rx="3"
          class="bar bar--started"
        />
        <rect
          :x="group.succeededX"
          :y="group.succeededY"
          :width="chart.barW"
          :height="Math.max(group.succeededH, group.succeeded > 0 ? 2 : 0)"
          rx="3"
          class="bar bar--ok"
        />
        <text
          v-if="group.started > 0"
          class="val-label"
          :x="group.startedX + chart.barW / 2"
          :y="group.startedY - 5"
          text-anchor="middle"
        >{{ format(group.started) }}</text>
        <text
          v-if="group.succeeded > 0"
          class="val-label"
          :x="group.succeededX + chart.barW / 2"
          :y="group.succeededY - 5"
          text-anchor="middle"
        >{{ format(group.succeeded) }}</text>
        <text
          v-if="sparseAxisTick(index, chart.groups.length)"
          :x="group.cx"
          :y="chart.H - 13"
          text-anchor="middle"
          class="tick"
        >{{ group.label }}</text>
      </g>

      <path :d="chart.rateLine" class="rate-line" />
      <circle
        v-for="(group, index) in chart.groups"
        :key="'rate-' + group.label + index"
        :cx="group.cx"
        :cy="group.rateY"
        :r="hoverIdx === index ? 5 : 3.5"
        class="rate-dot"
      />
      <text
        v-for="(group, index) in chart.groups"
        :key="'rate-n-' + group.label + index"
        class="val-rate"
        :x="group.cx + 8"
        :y="group.rateY - 6"
      >{{ group.rate }}%</text>

      <g v-if="tipBox" class="tooltip">
        <rect
          :x="tipBox.x"
          :y="tipBox.y"
          :width="tipBox.tw"
          :height="tipBox.th"
          rx="6"
          class="tooltip__bg"
        />
        <text :x="tipBox.x + 10" :y="tipBox.y + 20" class="tooltip__text">
          {{ tipBox.g.label }} · 创建 {{ format(tipBox.g.started) }} · 完成 {{ format(tipBox.g.succeeded) }}
        </text>
        <text :x="tipBox.x + 10" :y="tipBox.y + 38" class="tooltip__text">
          失败 {{ format(tipBox.g.failed) }}
        </text>
        <text :x="tipBox.x + 10" :y="tipBox.y + 56" class="tooltip__text tooltip__text--rate">
          完成率 {{ tipBox.g.rate }}%
        </text>
      </g>
    </svg>
  </div>
  <div v-else class="empty">这个窗口没有样本</div>
</template>

<style scoped>
.combo-chart { width: 100%; position: relative; overflow: visible; flex: 1; display: flex; flex-direction: column; min-height: 220px; }
.combo-chart.fill { height: 100%; }
.combo-chart.fill .chart { flex: 1; height: 100%; min-height: 240px; }
.legend {
  display: flex;
  justify-content: center;
  flex-wrap: wrap;
  gap: 18px;
  margin-bottom: 4px;
  color: var(--color-text-secondary, var(--ops-muted));
  font-size: 12px;
}
.legend span { display: inline-flex; align-items: center; gap: 6px; }
.legend__bar { width: 12px; height: 8px; border-radius: 2px; }
.legend__bar--started { background: var(--ops-blue); }
.legend__bar--ok { background: var(--ops-green); }
.legend__line { width: 16px; border-top: 2px solid var(--ops-amber); position: relative; }
.legend__line::after {
  content: '';
  position: absolute;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--ops-amber);
  top: -3.5px;
  left: 5px;
}
.chart { display: block; width: 100%; height: auto; overflow: visible; }
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
.group { transition: opacity 0.15s ease; }
.group.dim { opacity: 0.3; }
.bar { transform-origin: bottom; transform-box: fill-box; animation: bar-in 0.55s ease both; }
.bar--started { fill: var(--ops-blue); }
.bar--ok { fill: var(--ops-green); }
.val-label {
  fill: var(--color-text-primary, var(--ops-title));
  font-size: 9.5px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  pointer-events: none;
}
.val-rate {
  fill: var(--ops-amber);
  font-size: 10px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  pointer-events: none;
  paint-order: stroke;
  stroke: var(--color-bg-card, var(--ops-panel));
  stroke-width: 3px;
}
.rate-line {
  fill: none;
  stroke: var(--ops-amber);
  stroke-width: 2.5;
  stroke-linecap: round;
  stroke-linejoin: round;
  pointer-events: none;
}
.rate-dot {
  fill: var(--ops-amber);
  stroke: var(--color-bg-card, var(--ops-panel));
  stroke-width: 2;
  pointer-events: none;
  transition: r 0.15s ease;
}
.tooltip { pointer-events: none; }
.tooltip__bg { fill: #1f2329; opacity: 0.96; }
.tooltip__text { fill: #fff; font-size: 10px; }
.tooltip__text--rate { fill: var(--ops-amber); font-weight: 700; }
@keyframes bar-in {
  from { transform: scaleY(0); opacity: 0.4; }
  to { transform: scaleY(1); opacity: 1; }
}
.empty {
  min-height: 180px;
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
