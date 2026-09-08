import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent } from 'vue'
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

import QuotaChip from '../src/components/QuotaChip.vue'
import MergePreview from '../src/components/MergePreview.vue'
import PatchPopover from '../src/components/PatchPopover.vue'
import PdfInspectPanel from '../src/components/PdfInspectPanel.vue'
import CopyId from '../src/components/CopyId.vue'
import PriorityWhy from '../src/components/PriorityWhy.vue'
import QuotaLedger from '../src/components/QuotaLedger.vue'

describe('QuotaChip', () => {
  it('shows remaining quota and the empty hint', () => {
    const wrapper = mount(QuotaChip, { props: { quota: 0, large: true } })
    expect(wrapper.get('.quota-chip-num').text()).toBe('0')
    expect(wrapper.text()).toContain('额度用完了')
    expect(wrapper.text()).toContain('剩余额度')
  })
})

describe('MergePreview', () => {
  it('renders the score and can switch to changes-only', async () => {
    const wrapper = mount(MergePreview, {
      props: {
        official: 'keep\nold\nend',
        preview: 'keep\nnew\nend',
        score: 82,
        grade: 'B',
        points: [{ delta: 8, label: '复核确认已解决 1 项' }],
        reasons: [{ index: 1, why: '减少过强表述', original: 'old', proposed: 'new' }],
        mode: 'candidate'
      }
    })
    expect(wrapper.get('.merge-score').text()).toContain('82')
    expect(wrapper.get('.merge-score').text()).toContain('B')
    expect(wrapper.text()).toContain('减少过强表述')
    await wrapper.findAll('.pager-pill')[1].trigger('click')
    expect(wrapper.text()).toContain('未改')
  })
})

describe('PatchPopover', () => {
  it('emits close and shows a delete placeholder', async () => {
    const wrapper = mount(PatchPopover, {
      props: { proposed: '', reason: '去掉套话' },
      attachTo: document.body
    })
    expect(document.body.textContent).toContain('（删除这段）')
    expect(document.body.textContent).toContain('去掉套话')
    const close = document.body.querySelector('.patch-pop-close')
    expect(close).toBeTruthy()
    await close?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })
})

describe('PdfInspectPanel', () => {
  it('lists page spec, figures, DPI and Vision flags', async () => {
    const wrapper = mount(PdfInspectPanel, {
      props: {
        inspect: {
          pagePreview: true,
          hasFile: true,
          pageSpec: 'LETTER',
          pageCount: 2,
          needsVision: true,
          pages: [
            { page: 1, widthPt: 612, heightPt: 792, spec: 'LETTER' },
            { page: 2, widthPt: 612, heightPt: 792, spec: 'LETTER' }
          ],
          figures: [{
            page: 1,
            number: 1,
            name: 'Im1',
            width: 80,
            height: 80,
            approxDpi: 9.4,
            caption: 'Blurry architecture diagram.',
            needsVision: true,
            anchor: 'p1-Im1'
          }],
          captions: [{ number: 1, caption: 'Blurry architecture diagram.' }]
        }
      }
    })
    expect(wrapper.text()).toContain('PDF 页与图表')
    expect(wrapper.text()).toContain('US Letter')
    expect(wrapper.text()).toContain('2 页')
    expect(wrapper.text()).toContain('80×80 px · 9.4 DPI')
    expect(wrapper.text()).toContain('需 Vision')
    expect(wrapper.text()).toContain('Blurry architecture diagram.')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('open')).toHaveLength(1)
  })

  it('does not pretend Word or Markdown have a page preview', () => {
    const wrapper = mount(PdfInspectPanel, {
      props: { inspect: { format: 'MD', pagePreview: false } }
    })
    expect(wrapper.text()).toBe('')
  })
})

describe('CopyId', () => {
  it('renders a monospace id and copies it', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    const wrapper = mount(CopyId, { props: { label: '流水 ID', value: 88 } })
    expect(wrapper.text()).toContain('流水 ID')
    expect(wrapper.text()).toContain('88')
    await wrapper.get('button').trigger('click')
    expect(writeText).toHaveBeenCalledWith('88')
  })
})

describe('PriorityWhy', () => {
  it('shows why-high, suggest-fix and evidence sentences on a high-priority card', () => {
    const wrapper = mount(PriorityWhy, {
      props: {
        whyHigh: '优先级高：会改引用结论与作者责任。',
        suggestFix: '核对幽灵引用',
        evidence: {
          location: 'references · 10.0000/ghost.doi',
          excerpt: 'see 10.0000/ghost.doi',
          basis: 'Evidence ev-ghost · CROSSREF · NOT_VERIFIED'
        }
      }
    })
    expect(wrapper.text()).toContain('为什么高')
    expect(wrapper.text()).toContain('会改引用结论与作者责任')
    expect(wrapper.text()).toContain('建议怎么改')
    expect(wrapper.text()).toContain('核对幽灵引用')
    expect(wrapper.text()).toContain('定位')
    expect(wrapper.text()).toContain('原文')
    expect(wrapper.text()).toContain('规则依据')
    expect(wrapper.text()).toContain('Evidence ev-ghost')
  })
})

describe('QuotaLedger ids', () => {
  beforeEach(() => {
    api.mockReset()
    api.mockResolvedValue({
      items: [{
        id: 88,
        delta: 10,
        reason: 'PURCHASE',
        refId: 'order-ZY1',
        createdAt: '2026-09-07T03:00:00+08:00'
      }],
      total: 1,
      page: 1,
      size: 10
    })
  })

  it('shows copyable ledger and order ids', async () => {
    const Blank = defineComponent({ template: '<div />' })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: Blank },
        { path: '/orders/:id', component: Blank },
        { path: '/reviews/:id', component: Blank }
      ]
    })
    await router.push('/')
    await router.isReady()
    const wrapper = mount(QuotaLedger, { global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.text()).toContain('流水 ID')
    expect(wrapper.text()).toContain('88')
    expect(wrapper.text()).toContain('订单 ID')
    expect(wrapper.text()).toContain('ZY1')
    wrapper.unmount()
  })
})
