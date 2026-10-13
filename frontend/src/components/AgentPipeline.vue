<template>
  <ol class="agent-pipe" :class="['is-' + mode, { compact }]" :aria-label="label">
    <li
      v-for="(node, i) in nodes"
      :key="node.id"
      class="ap-node"
      :data-st="node.state"
      :style="{ '--i': i }"
    >
      <span v-if="i > 0" class="ap-link" :data-st="linkState(i)" aria-hidden="true" />
      <span class="ap-tile">
        <Icon :name="node.icon" :size="compact ? 16 : 18" />
        <span v-if="node.state === 'done'" class="ap-badge ok" aria-hidden="true">
          <Icon name="check" :size="9" :stroke="3" />
        </span>
        <span v-else-if="node.state === 'failed'" class="ap-badge bad" aria-hidden="true">
          <Icon name="x" :size="9" :stroke="3" />
        </span>
      </span>
      <span class="ap-name">{{ node.name }}</span>
      <span v-if="!compact && node.role" class="ap-role">{{ node.role }}</span>
      <span v-else-if="node.stateLabel" class="ap-role ap-state">{{ node.stateLabel }}</span>
    </li>
  </ol>
</template>

<script setup>
import { computed } from 'vue'
import Icon from './Icon.vue'
import { AGENT_META } from '../agents'

const props = defineProps({
  /** showcase：展示全链；select：点亮 active 中的节点；live：按 nodes 的 state 着色 */
  mode: { type: String, default: 'showcase' },
  /** select 模式：参与本次 Workflow 的 Agent（id 或中文名均可） */
  active: { type: Array, default: () => [] },
  /** live 模式：[{ id, name, state, label }]，来自 pipelineNodes */
  live: { type: Array, default: () => [] },
  compact: { type: Boolean, default: false },
  label: { type: String, default: '审校 Agent 流水线' }
})

const nodes = computed(() => {
  if (props.mode === 'live') {
    return props.live.map((n) => {
      const meta = AGENT_META.find((m) => m.id === n.id) || {}
      return {
        id: n.id,
        name: n.name || meta.name || n.id,
        icon: meta.icon || 'sparkles',
        role: '',
        state: n.state || 'pending',
        stateLabel: n.label || ''
      }
    })
  }
  if (props.mode === 'select') {
    const on = new Set(props.active.map(String))
    return AGENT_META.map((m) => ({
      ...m,
      state: on.has(m.id) || on.has(m.name) ? 'on' : 'off'
    }))
  }
  return AGENT_META.map((m) => ({ ...m, state: 'idle' }))
})

function linkState(i) {
  const prev = nodes.value[i - 1]?.state
  const cur = nodes.value[i]?.state
  if (props.mode === 'live') {
    if (prev === 'done' && cur === 'done') return 'done'
    if (prev === 'done' && (cur === 'current' || cur === 'failed')) return cur === 'failed' ? 'failed' : 'flow'
    return 'idle'
  }
  if (props.mode === 'select') return prev === 'on' && cur === 'on' ? 'flow' : 'off'
  return 'flow'
}
</script>

<style scoped>
.agent-pipe {
  --tile: 44px;
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: minmax(0, 1fr);
  gap: 0;
}
.agent-pipe.compact { --tile: 34px; }

.ap-node {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 6px;
  min-width: 0;
  padding: 0 4px;
  animation: ap-in var(--dur-4) var(--ease-out) both;
  animation-delay: calc(var(--i) * 60ms);
}
.ap-link {
  position: absolute;
  top: calc(var(--tile) / 2);
  right: calc(50% + var(--tile) / 2 + 6px);
  left: calc(-50% + var(--tile) / 2 + 6px);
  height: 2px;
  border-radius: 2px;
  background: var(--border-strong);
  overflow: hidden;
}
.ap-link[data-st="flow"] {
  background: linear-gradient(90deg, var(--accent-soft-2), var(--accent), var(--accent-soft-2));
  background-size: 48px 100%;
  animation: zy-flow 1.2s linear infinite;
}
.ap-link[data-st="done"] { background: color-mix(in srgb, var(--ok) 70%, transparent); }
.ap-link[data-st="failed"] { background: color-mix(in srgb, var(--danger) 60%, transparent); }
.ap-link[data-st="off"] {
  background: repeating-linear-gradient(90deg, var(--border-strong) 0 4px, transparent 4px 8px);
}

.ap-tile {
  position: relative;
  display: grid;
  place-items: center;
  width: var(--tile);
  height: var(--tile);
  border-radius: calc(var(--tile) * 0.3);
  border: 1px solid var(--border-strong);
  background: var(--surface);
  color: var(--text-2);
  box-shadow: var(--shadow-xs), var(--inset-highlight);
  transition: all var(--dur-3) var(--ease-out);
}
.ap-badge {
  position: absolute;
  right: -5px;
  bottom: -5px;
  display: grid;
  place-items: center;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  color: #fff;
  border: 2px solid var(--surface);
}
.ap-badge.ok { background: var(--ok); }
.ap-badge.bad { background: var(--danger); }
.ap-name {
  font-size: var(--fs-12);
  font-weight: 600;
  color: var(--text);
  white-space: nowrap;
}
.ap-role {
  font-size: 11px;
  color: var(--text-3);
  line-height: 1.35;
  max-width: 11em;
}
.compact .ap-name { font-size: 11.5px; }
.agent-pipe { container-type: inline-size; }
@container (max-width: 520px) {
  .ap-name {
    width: 2.1em;
    white-space: normal;
    line-height: 1.3;
    font-size: 11px;
  }
  .ap-role { display: none; }
}

/* showcase：依次点亮的呼吸 */
.is-showcase .ap-tile {
  color: var(--accent-text);
  background: linear-gradient(180deg, var(--surface), var(--surface-2));
  animation: ap-glow 7s var(--ease-in-out) infinite;
  animation-delay: calc(var(--i) * 1s);
}

/* select */
.ap-node[data-st="on"] .ap-tile {
  color: var(--accent-text);
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
  background: var(--accent-soft);
  box-shadow: 0 0 0 4px var(--accent-soft), var(--inset-highlight);
}
.ap-node[data-st="off"] { opacity: 0.38; }
.ap-node[data-st="off"] .ap-tile { border-style: dashed; box-shadow: none; }

/* live */
.ap-node[data-st="done"] .ap-tile {
  color: var(--ok);
  border-color: color-mix(in srgb, var(--ok) 40%, transparent);
  background: var(--ok-soft);
}
.ap-node[data-st="current"] .ap-tile {
  color: #fff;
  border-color: transparent;
  background: var(--accent-grad);
  animation: zy-ring 1.6s var(--ease-out) infinite;
}
.ap-node[data-st="failed"] .ap-tile {
  color: var(--danger);
  border-color: color-mix(in srgb, var(--danger) 45%, transparent);
  background: var(--danger-soft);
}
.ap-node[data-st="pending"] .ap-tile { color: var(--text-3); }
.ap-node[data-st="pending"] .ap-name { color: var(--text-3); }
.ap-state { font-weight: 500; }
.ap-node[data-st="current"] .ap-state { color: var(--accent-text); }
.ap-node[data-st="failed"] .ap-state { color: var(--danger); }
.ap-node[data-st="done"] .ap-state { color: var(--ok); }

@keyframes ap-in {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: none; }
}
@keyframes ap-glow {
  0%, 18%, 100% { box-shadow: var(--shadow-xs), var(--inset-highlight); border-color: var(--border-strong); }
  6% {
    box-shadow: 0 0 0 5px var(--accent-soft), 0 8px 24px -8px var(--accent);
    border-color: color-mix(in srgb, var(--accent) 55%, transparent);
  }
}
</style>
