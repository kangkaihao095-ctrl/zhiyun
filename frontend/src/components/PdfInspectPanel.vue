<template>
  <div v-if="inspect?.pagePreview" class="pdf-inspect">
    <div class="ver-toolbar">
      <div>
        <p class="panel-label">PDF 页与图表</p>
        <h3>{{ heading }}</h3>
        <p class="muted">
          页规格、DPI、编号、题注由程序检查；只有模糊或拉伸才标需 Vision。不是 PDF 阅读器。
        </p>
      </div>
      <button v-if="inspect.hasFile" class="btn btn-ghost" type="button" @click="$emit('open')">打开原件</button>
    </div>

    <div class="pdf-inspect-stats">
      <span>页规格 {{ inspect.pageSpec || '未知' }}</span>
      <span>{{ inspect.pageCount || 0 }} 页</span>
      <span>{{ (inspect.figures || []).length }} 张图</span>
      <span>{{ inspect.needsVision ? '部分需 Vision' : '程序检查即可' }}</span>
    </div>

    <p class="pdf-inspect-sub">页面</p>
    <div class="pdf-inspect-rows">
      <div v-for="p in inspect.pages || []" :key="'p' + p.page" class="pdf-inspect-row">
        <span>第 {{ p.page }} 页</span>
        <span>{{ specLabel(p.spec) }}</span>
        <span class="muted">{{ Math.round(p.widthPt) }}×{{ Math.round(p.heightPt) }} pt</span>
      </div>
    </div>

    <p class="pdf-inspect-sub">图表</p>
    <div v-if="!(inspect.figures || []).length" class="muted">程序未扫到嵌入图。题注若写在正文里仍会列在下面。</div>
    <div v-else class="pdf-inspect-rows">
      <div v-for="fig in inspect.figures" :key="fig.anchor || (fig.page + '-' + fig.name)" class="pdf-inspect-row">
        <span>图 {{ fig.number }} · p.{{ fig.page }}</span>
        <span>{{ fig.width }}×{{ fig.height }} px · {{ fig.approxDpi }} DPI</span>
        <span :class="fig.needsVision ? 'pdf-inspect-vision' : 'muted'">
          {{ fig.needsVision ? '需 Vision' : '程序可判定' }}
        </span>
        <p v-if="fig.caption" class="pdf-inspect-cap">{{ fig.caption }}</p>
        <p v-else class="muted pdf-inspect-cap">无抽出题注</p>
      </div>
    </div>

    <template v-if="orphanCaptions.length">
      <p class="pdf-inspect-sub">正文题注</p>
      <div class="pdf-inspect-rows">
        <div v-for="c in orphanCaptions" :key="'c' + c.number" class="pdf-inspect-row">
          <span>Figure {{ c.number }}</span>
          <span>{{ c.caption }}</span>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  inspect: { type: Object, default: () => ({}) }
})
defineEmits(['open'])

const heading = computed(() => {
  const spec = specLabel(props.inspect?.pageSpec)
  const n = Number(props.inspect?.pageCount) || 0
  return spec + ' · ' + n + ' 页'
})

const orphanCaptions = computed(() => {
  const caps = Array.isArray(props.inspect?.captions) ? props.inspect.captions : []
  const figs = Array.isArray(props.inspect?.figures) ? props.inspect.figures : []
  const used = new Set(figs.map((f) => Number(f.number)).filter((n) => n > 0))
  return caps.filter((c) => !used.has(Number(c.number)))
})

function specLabel(spec) {
  return { A4: 'A4', LETTER: 'US Letter', MIXED: '混用规格', OTHER: '其他规格', UNKNOWN: '未知规格' }[spec] || spec || '未知规格'
}
</script>
