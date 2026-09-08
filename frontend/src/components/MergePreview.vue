<template>
  <section class="merge-panel panel">
    <div class="merge-head">
      <div>
        <p class="panel-label">合并预览</p>
        <h3>即将把候选稿并入正式稿</h3>
        <p class="muted">
          左侧是当前主干（正式稿），右侧是{{ modeHint }}。还没有写入正式稿，需你全部接受或部分接受。
        </p>
      </div>
      <div class="merge-score" :data-g="grade">
        <strong>{{ score }}</strong>
        <span>{{ grade }}</span>
      </div>
    </div>

    <div class="merge-points" v-if="points.length">
      <p class="merge-sub">评分依据</p>
      <ul>
        <li v-for="(p, i) in points" :key="i" :class="p.delta >= 0 ? 'plus' : 'minus'">
          <em>{{ p.delta > 0 ? '+' : '' }}{{ p.delta }}</em>
          {{ p.label }}
        </li>
      </ul>
    </div>

    <div class="merge-why" v-if="reasons.length">
      <p class="merge-sub">为什么新版本更好</p>
      <ol>
        <li v-for="r in reasons" :key="r.index">
          <p class="merge-why-t">{{ r.why }}</p>
          <p class="muted" v-if="r.original || r.proposed">
            <template v-if="r.original">现在：{{ r.original }}</template>
            <template v-if="r.proposed"> → 建议：{{ r.proposed }}</template>
          </p>
        </li>
      </ol>
    </div>
    <p class="muted" v-else>这次没有对应到句子的说明。请对照左侧问题和下面的差异。</p>

    <div class="merge-tools">
      <button type="button" class="pager-pill" :class="{ on: !onlyChanges }" @click="onlyChanges = false">全文对照</button>
      <button type="button" class="pager-pill" :class="{ on: onlyChanges }" @click="onlyChanges = true">只看变更</button>
    </div>

    <div class="merge-legend">
      <span class="leg del">删除（正式稿有、预览没有）</span>
      <span class="leg add">新增（接受后会写入）</span>
    </div>

    <div class="merge-diff" role="region" aria-label="正式稿与预览稿差异">
      <template v-for="(row, i) in visibleRows" :key="i">
        <div v-if="row.type === 'skip'" class="diff-skip">··· 未改 {{ row.count }} 行</div>
        <div v-else class="diff-line" :class="row.type">
          <span class="diff-mark">{{ row.type === 'del' ? '−' : row.type === 'add' ? '+' : ' ' }}</span>
          <span class="diff-text">{{ row.text || ' ' }}</span>
        </div>
      </template>
      <p v-if="!rows.length" class="muted">两边正文都是空的。</p>
    </div>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'
import { diffLines, visibleDiffRows } from '../merge-diff'

const props = defineProps({
  official: { type: String, default: '' },
  preview: { type: String, default: '' },
  score: { type: Number, default: 0 },
  grade: { type: String, default: 'D' },
  points: { type: Array, default: () => [] },
  reasons: { type: Array, default: () => [] },
  mode: { type: String, default: 'candidate' }
})

const onlyChanges = ref(false)

const modeHint = computed(() => props.mode === 'partial'
  ? '若接受你勾选的那些修改后的预览'
  : '若全部接受后的候选稿')

const rows = computed(() => diffLines(props.official, props.preview))
const visibleRows = computed(() => visibleDiffRows(rows.value, onlyChanges.value))
</script>
