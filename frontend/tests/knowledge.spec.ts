import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.fn()
vi.mock('../src/api.js', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../src/api.js')>()
  return {
    ...actual,
    api: (...args: unknown[]) => api(...args)
  }
})
vi.mock('../src/toast.js', () => ({ toast: vi.fn() }))

import Knowledge from '../src/views/Knowledge.vue'
import {
  catalogDocs,
  looksLikeFullDump,
  overviewGroups
} from '../src/knowledge-overview'

const DUMP = '\\documentclass[10pt,conference]{IEEEtran}\n'.repeat(40)

const catalog = {
  publicChunks: 168,
  note: '公共知识全局一份（scope=PUBLIC）。',
  groups: [
    {
      id: 'venue',
      title: '期刊规范',
      summary: 'IEEE、ACM、ACL 等投稿模板、页规格与实验门槛。',
      highlights: ['排版与页规格', '公式与字体', '实验 vs 仿真'],
      fileCount: 2
    },
    {
      id: 'skill',
      title: '写作 skill',
      summary: '英文润色、中文润色、中译英与 LaTeX 审查。',
      highlights: ['英文润色', '中文润色', '中译英', 'LaTeX 审查'],
      fileCount: 1
    },
    {
      id: 'source',
      title: '来源与索引',
      summary: '全局一份 PUBLIC ES，不是一租户一份库。',
      highlights: ['仓库内置 3 篇', '公共切片 168 条', '运营上传 0 份'],
      fileCount: 0
    }
  ],
  bundled: [
    {
      filename: '02-ieee.md',
      title: 'IEEE 会议与期刊投稿规范',
      summary: 'IEEEtran 双栏 Times。',
      sections: ['LaTeX', 'Word'],
      category: 'venue',
      content: DUMP
    },
    {
      filename: '06-acl.md',
      title: 'ACL / *ACL 投稿规范',
      summary: 'A4 双栏 Times。',
      sections: ['必须用官方样式'],
      category: 'venue'
    },
    {
      filename: '25-prompt-en-polish.md',
      title: 'Prompt：英文润色',
      summary: '提升英文严谨性。',
      sections: ['任务'],
      category: 'skill',
      content: DUMP
    }
  ],
  uploaded: []
}

describe('knowledge overview helpers', () => {
  it('groups venues, writing skills and source counts', () => {
    const groups = overviewGroups(catalog)
    expect(groups.map((g) => g.title)).toEqual(['期刊规范', '写作 skill', '来源与索引'])
    expect(groups[1].highlights).toContain('英文润色')
    expect(groups[2].highlights.join(' ')).toContain('168')
  })

  it('derives the same groups when the API omits them', () => {
    const groups = overviewGroups({
      bundled: catalog.bundled,
      uploaded: [],
      publicChunks: 168
    })
    expect(groups[0].title).toBe('期刊规范')
    expect(groups[1].title).toBe('写作 skill')
    expect(groups[1].summary).toContain('中译英')
  })

  it('does not treat the overview as a markdown dump', () => {
    const text = overviewGroups(catalog).map((g) => `${g.title} ${g.summary} ${g.highlights.join(' ')}`).join('\n')
    expect(looksLikeFullDump(text)).toBe(false)
    expect(looksLikeFullDump(DUMP)).toBe(true)
  })

  it('lists filenames only in the catalog, not as a body dump', () => {
    const docs = catalogDocs(catalog, 'venue')
    expect(docs.map((d) => d.filename)).toEqual(['02-ieee.md', '06-acl.md'])
    expect(docs.every((d) => !looksLikeFullDump(d.summary || ''))).toBe(true)
  })
})

describe('Knowledge view', () => {
  beforeEach(() => {
    api.mockReset()
    api.mockResolvedValue(structuredClone(catalog))
  })

  async function mountOps() {
    const wrapper = mount(Knowledge, { props: { me: { operator: true, email: 'demo@zhiyun.dev' } } })
    await flushPromises()
    return wrapper
  }

  it('defaults to grouped essence, not a full dump', async () => {
    const wrapper = await mountOps()
    const overview = wrapper.get('[data-testid="knowledge-overview"]')
    expect(overview.text()).toContain('期刊规范')
    expect(overview.text()).toContain('写作 skill')
    expect(overview.text()).toContain('英文润色')
    expect(overview.text()).toContain('实验 vs 仿真')
    expect(overview.text()).toContain('公共切片 168 条')
    expect(wrapper.text()).not.toContain('02-ieee.md')
    expect(wrapper.text()).not.toContain('25-prompt-en-polish.md')
    expect(looksLikeFullDump(wrapper.text())).toBe(false)
    expect(wrapper.text()).not.toContain('documentclass')
    expect(wrapper.find('[data-testid="knowledge-catalog"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('expands the catalog and shows a title plus short summary', async () => {
    const wrapper = await mountOps()
    await wrapper.get('[data-testid="knowledge-show-all"]').trigger('click')
    await flushPromises()
    const dir = wrapper.get('[data-testid="knowledge-catalog"]')
    expect(dir.text()).toContain('02-ieee.md')
    expect(dir.text()).toContain('IEEE 会议与期刊投稿规范')
    expect(looksLikeFullDump(dir.text())).toBe(false)

    await dir.findAll('.zy-select-option')[0].trigger('click')
    const preview = wrapper.get('[data-testid="knowledge-preview"]')
    expect(preview.text()).toContain('IEEE 会议与期刊投稿规范')
    expect(preview.text()).toContain('IEEEtran 双栏 Times')
    expect(preview.text()).toContain('LaTeX')
    expect(preview.text()).not.toContain('documentclass')
    expect(looksLikeFullDump(preview.text())).toBe(false)
    wrapper.unmount()
  })
})
