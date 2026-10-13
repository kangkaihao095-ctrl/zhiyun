/** 七个论文 Agent 的展示元数据；顺序与 FULL_REVIEW 链一致，名称与 review.js 的 AGENT_NAME 对齐。 */
export const AGENT_META = [
  { id: 'CITATION_INTEGRITY', name: '引用核验', icon: 'quote', role: 'Crossref 逐条核 DOI' },
  { id: 'FIGURE_PDF', name: '图表检查', icon: 'image', role: 'DPI · 题注 · Vision' },
  { id: 'ACADEMIC_REVIEWER', name: '学术审稿', icon: 'scale', role: '按主张取证' },
  { id: 'ACADEMIC_STYLE', name: '语言润色', icon: 'pen', role: '去套话，保数字' },
  { id: 'REVISION_PLANNING', name: '改稿计划', icon: 'listChecks', role: '拆成修改任务' },
  { id: 'REVISION_EXECUTION', name: '修改执行', icon: 'wand', role: '只写候选稿' },
  { id: 'FINAL_VERIFICATION', name: '结果复核', icon: 'badgeCheck', role: '独立复核' }
]

export function agentIcon(idOrName) {
  const key = String(idOrName || '')
  return AGENT_META.find((m) => m.id === key || m.name === key)?.icon || 'sparkles'
}

/** 三条 Workflow 的展示图标 */
export const WORKFLOW_ICON = {
  CITATION_ONLY: 'quote',
  QUICK_REVIEW: 'zap',
  FULL_REVIEW: 'layers'
}
