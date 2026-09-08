export type KnowledgeCategory = 'venue' | 'skill' | 'source' | 'ops'

export type KnowledgeDoc = {
  id?: number
  source?: string
  filename: string
  title?: string
  summary?: string
  sections?: string[]
  category?: KnowledgeCategory | string
  chars?: number
  content?: string
  updatedAt?: string
}

export type KnowledgeGroup = {
  id: string
  title: string
  summary: string
  highlights: string[]
  items?: string[]
  fileCount?: number
}

export type KnowledgeCatalog = {
  bundled?: KnowledgeDoc[]
  uploaded?: KnowledgeDoc[]
  publicChunks?: number
  groups?: KnowledgeGroup[]
  note?: string
}

export const CATALOG_FILTERS = [
  { id: 'all', label: '全部' },
  { id: 'venue', label: '期刊规范' },
  { id: 'skill', label: '写作 skill' },
  { id: 'source', label: '平台与索引' },
  { id: 'ops', label: '已上传' }
] as const

const VENUE_PREFIX = /^(02|03|04|05|06|07|08|14|15|16|17|18)-/
const SOURCE_PREFIX = /^(00|01|33)-/

export function categoryOf(filename: string): KnowledgeCategory {
  const name = String(filename || '').toLowerCase()
  if (SOURCE_PREFIX.test(name)) return 'source'
  if (VENUE_PREFIX.test(name) || name.includes('systems') || name.includes('venue')) return 'venue'
  return 'skill'
}

export function overviewGroups(catalog: KnowledgeCatalog): KnowledgeGroup[] {
  if (catalog.groups && catalog.groups.length) {
    return catalog.groups
  }
  const bundled = catalog.bundled || []
  const uploaded = catalog.uploaded || []
  const chunks = catalog.publicChunks ?? 0
  const venueFiles = bundled.filter((row) => categoryOf(row.filename) === 'venue')
  const skillFiles = bundled.filter((row) => categoryOf(row.filename) === 'skill')
  const sourceFiles = bundled.filter((row) => categoryOf(row.filename) === 'source')
  return [
    {
      id: 'venue',
      title: '期刊规范',
      summary: 'IEEE、ACM、ACL、NeurIPS、ICML 等投稿模板、页规格与实验门槛。',
      highlights: ['排版与页规格', '公式与字体', '实验 vs 仿真'],
      fileCount: venueFiles.length
    },
    {
      id: 'skill',
      title: '写作 skill',
      summary: '英文润色、中文润色、中译英与 LaTeX 审查。',
      highlights: ['英文润色', '中文润色', '中译英', 'LaTeX 审查'],
      fileCount: skillFiles.length
    },
    {
      id: 'source',
      title: '来源与索引',
      summary: '全局一份 PUBLIC ES，不是一租户一份库。',
      highlights: [
        `仓库内置 ${bundled.length} 篇`,
        `公共切片 ${chunks} 条`,
        `运营上传 ${uploaded.length} 份`
      ],
      fileCount: sourceFiles.length
    }
  ]
}

export function catalogDocs(catalog: KnowledgeCatalog, filter = 'all'): KnowledgeDoc[] {
  const bundled = (catalog.bundled || []).map((row) => ({
    ...row,
    source: row.source || 'classpath',
    category: row.category || categoryOf(row.filename)
  }))
  const uploaded = (catalog.uploaded || []).map((row) => ({
    ...row,
    source: row.source || 'ops',
    category: 'ops' as const
  }))
  const all = [...bundled, ...uploaded]
  if (filter === 'all') return all
  return all.filter((row) => String(row.category) === filter)
}

export function docKey(row: KnowledgeDoc): string {
  return `${row.source || 'classpath'}:${row.id ?? row.filename}`
}

export function previewOf(row: KnowledgeDoc | null | undefined): { title: string; summary: string; sections: string[] } {
  if (!row) return { title: '', summary: '', sections: [] }
  return {
    title: row.title || row.filename,
    summary: row.summary || '仓库内置专章，全文由审校 RAG 检索，本页不展开原文。',
    sections: Array.isArray(row.sections) ? row.sections.slice(0, 10) : []
  }
}

/** 运营页不应渲染 md / ES 原文。 */
export function looksLikeFullDump(text: string): boolean {
  const raw = String(text || '')
  if (/\\documentclass/.test(raw)) return true
  if (/# IEEE 会议与期刊投稿规范/.test(raw) && raw.includes('IEEEtran HOWTO')) return true
  if ((raw.match(/## /g) || []).length >= 8) return true
  return false
}
