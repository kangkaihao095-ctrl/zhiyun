<template>
  <div class="ops-wf" data-testid="trace-waterfall" @mousemove="onMove">
    <p class="ops-wf-cap">{{ caption }} · {{ layout.axisTicks[layout.axisTicks.length - 1] || '0ms' }}</p>
    <div class="ops-wf-axis" aria-hidden="true">
      <span class="ops-wf-axis-gap" />
      <div class="ops-wf-ticks">
        <span
          v-for="(tick, i) in layout.axisTicks"
          :key="'tick-' + i"
          :style="{ left: layout.gridPcts[i] + '%' }"
        >{{ tick }}</span>
      </div>
    </div>
    <button
      v-for="(bar, index) in layout.bars"
      :key="bar.agent"
      type="button"
      class="ops-wf-row"
      :class="{ on: selectedAgent === bar.agent, dim: selectedAgent && selectedAgent !== bar.agent }"
      :data-st="bar.state || spanState(bar.status)"
      :data-agent="bar.agent"
      :data-testid="'ops-span-' + bar.agent"
      @click="toggle(bar.agent)"
      @mouseenter="onEnter(bar, $event)"
      @mouseleave="onLeave"
    >
      <div class="ops-wf-name">
        <span class="ops-wf-svc">{{ bar.name }}</span>
        <span class="ops-wf-st">{{ bar.label || bar.status || 'span' }}</span>
      </div>
      <div class="ops-wf-track">
        <span
          v-for="pct in layout.gridPcts"
          :key="'g-' + bar.agent + pct"
          class="ops-wf-grid"
          :style="{ left: pct + '%' }"
        />
        <span
          class="ops-wf-bar"
          :data-st="bar.state || spanState(bar.status)"
          :style="barStyle(bar, index)"
        >
          <em>{{ durationOf(bar) }}</em>
        </span>
      </div>
    </button>
    <p v-if="!layout.bars.length" class="ops-placeholder ops-placeholder--compact">这条任务还没有 Agent span</p>
    <Transition name="ops-span">
      <dl v-if="selected" class="ops-span-detail" data-testid="ops-span-detail">
        <div v-for="field in selectedFields" :key="field.key">
          <dt>{{ field.key }}</dt>
          <dd>{{ field.value }}</dd>
        </div>
      </dl>
    </Transition>
    <div
      v-if="tip"
      class="ops-wf-tip"
      data-testid="ops-span-tip"
      :style="{ left: tip.left + 'px', top: tip.top + 'px' }"
    >
      <div><span>Agent</span><b>{{ tip.agent }}</b></div>
      <div><span>耗时</span><b>{{ tip.duration }}</b></div>
      <div><span>token</span><b>{{ tip.tokens }}</b></div>
      <div><span>tool</span><b>{{ tip.tools }}</b></div>
      <div><span>errorCode</span><b>{{ tip.errorCode }}</b></div>
      <div><span>状态</span><b>{{ tip.status }}</b></div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref } from 'vue'
import { layoutWaterfall, toolCountOf } from '../trace-waterfall'
import { formatDurationMs } from '../review.js'
import { spanColor, spanState } from '../observability.js'

const props = defineProps({
  nodes: { type: Array, default: () => [] },
  caption: { type: String, default: 'Trace · 相对任务开始' }
})

const selectedAgent = ref('')
const tip = ref(null)
const layout = computed(() => layoutWaterfall(props.nodes || []))
const selected = computed(() => layout.value.bars.find((bar) => bar.agent === selectedAgent.value) || null)

const selectedFields = computed(() => {
  const bar = selected.value
  if (!bar) return []
  return [
    { key: 'span', value: bar.name || bar.agent },
    { key: 'agent', value: bar.agent },
    { key: 'status', value: bar.status || bar.state },
    { key: 'duration', value: durationOf(bar) },
    { key: 'tokens', value: bar.tokens != null ? String(bar.tokens) : '' },
    { key: 'tools', value: String(toolCountOf(bar)) },
    { key: 'tool', value: bar.toolName },
    { key: 'errorCode', value: bar.errorCode },
    { key: 'error', value: bar.errorMessage },
    { key: 'checkpoint', value: bar.checkpoint ? 'true' : '' },
    { key: 'skipped', value: bar.skipped ? 'true' : '' },
    { key: 'fence', value: bar.fencingToken != null ? String(bar.fencingToken) : '' },
    { key: 'skill', value: bar.skillVersion },
    { key: 'prompt', value: bar.promptVersion }
  ].filter((row) => row.value !== '' && row.value != null)
})

function durationOf(bar) {
  return formatDurationMs(bar.durationMs) || '0ms'
}

function barStyle(bar, index) {
  const state = bar.state || spanState(bar.status)
  const width = Math.max(bar.widthPct, bar.durationMs > 0 ? 0.8 : 0)
  return {
    left: bar.leftPct + '%',
    width: width + '%',
    background: spanColor(bar.agent, state),
    '--wf-i': String(index || 0)
  }
}

function tipOf(bar) {
  return {
    agent: bar.name || bar.agent || '—',
    duration: durationOf(bar),
    tokens: bar.tokens != null && bar.tokens !== '' ? String(bar.tokens) : '—',
    tools: String(toolCountOf(bar)),
    errorCode: bar.errorCode || '—',
    status: bar.label || bar.status || bar.state || '—'
  }
}

function placeTip(wrap, ev) {
  if (!wrap) return { left: 12, top: 12 }
  const box = wrap.getBoundingClientRect()
  let left = ev.clientX - box.left + 12
  let top = ev.clientY - box.top - 10
  if (left > box.width - 180) left = box.width - 188
  if (left < 8) left = 8
  if (top < 8) top = ev.clientY - box.top + 18
  return { left, top }
}

function onEnter(bar, ev) {
  const wrap = ev.currentTarget.closest('.ops-wf')
  tip.value = { ...tipOf(bar), ...placeTip(wrap, ev) }
  nextTick(() => {
    if (!tip.value) return
    tip.value = { ...tip.value, ...placeTip(wrap, ev) }
  })
}

function onMove(ev) {
  if (!tip.value) return
  const wrap = ev.currentTarget
  tip.value = { ...tip.value, ...placeTip(wrap, ev) }
}

function onLeave() {
  tip.value = null
}

function toggle(agent) {
  selectedAgent.value = selectedAgent.value === agent ? '' : agent
}
</script>

<style scoped>
.ops-wf {
  position: relative;
  overflow: visible;
}
.ops-wf-row {
  transition: opacity 0.25s ease, background-color 0.25s ease, transform 0.25s ease;
}
.ops-wf-row.on {
  transform: translateX(4px);
}
.ops-wf-row.dim {
  opacity: 0.42;
}
.ops-wf-bar {
  transform-origin: left center;
  animation: ops-wf-in 0.28s ease both;
  animation-iteration-count: 1;
  animation-delay: calc(var(--wf-i, 0) * 36ms);
  transition: width 0.25s ease, opacity 0.25s ease, transform 0.25s ease, filter 0.25s ease;
}
.ops-wf-row.on .ops-wf-bar {
  transform: translateY(-1px);
  filter: brightness(1.08);
}
@keyframes ops-wf-in {
  from {
    transform: scaleX(0.16) translateX(-8px);
    opacity: 0;
  }
  to {
    transform: scaleX(1) translateX(0);
    opacity: 1;
  }
}
.ops-span-enter-active,
.ops-span-leave-active {
  transition: opacity 0.25s ease, transform 0.25s ease;
}
.ops-span-enter-from,
.ops-span-leave-to {
  opacity: 0;
  transform: translateY(6px);
}
.ops-wf-tip {
  position: absolute;
  z-index: 8;
  pointer-events: none;
  min-width: 148px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #1f2329;
  color: #fff;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.18);
  font-size: 11px;
  line-height: 1.45;
}
.ops-wf-tip div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}
.ops-wf-tip span {
  opacity: 0.72;
}
.ops-wf-tip b {
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
</style>
