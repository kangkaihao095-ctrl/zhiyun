import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.fn()
vi.mock('../src/api.js', () => ({
  api: (...args: unknown[]) => api(...args),
  fetchAuthBlob: vi.fn()
}))

import Manuscript from '../src/views/Manuscript.vue'

const Blank = defineComponent({ template: '<div />' })

const pdfInspect = {
  manuscriptId: 4,
  versionNo: 1,
  format: 'PDF',
  pagePreview: true,
  hasFile: true,
  pageSpec: 'LETTER',
  pageCount: 1,
  needsVision: true,
  pages: [{ page: 1, widthPt: 612, heightPt: 792, spec: 'LETTER' }],
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

function jsonFor(path: string) {
  if (path === '/workflows') {
    return { workflows: [{ id: 'CITATION_ONLY', name: '引用核验', summary: '核 DOI', agents: ['引用核验'], capPoints: 3, useWhen: '先查文献' }] }
  }
  if (path === '/manuscripts/4') {
    return {
      manuscript: { id: 4, title: 'camera-ready.pdf', currentVersion: 1 },
      versions: [{
        id: 11,
        versionNo: 1,
        status: 'OFFICIAL',
        current: true,
        format: 'PDF',
        hasFile: true,
        filename: 'camera-ready.pdf',
        contentText: 'Figure 1. Blurry architecture diagram.'
      }]
    }
  }
  if (path === '/manuscripts/5') {
    return {
      manuscript: { id: 5, title: 'draft.md', currentVersion: 1 },
      versions: [{
        id: 12,
        versionNo: 1,
        status: 'OFFICIAL',
        current: true,
        format: 'MD',
        hasFile: true,
        filename: 'draft.md',
        contentText: '# Abstract\nWord and Markdown keep extracted text.'
      }]
    }
  }
  if (path === '/manuscripts/4/versions/1/inspect') return pdfInspect
  return {}
}

async function mountManuscript(id: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/manuscripts/:id', component: Manuscript },
      { path: '/history', component: Blank },
      { path: '/reviews/:id', component: Blank }
    ]
  })
  await router.push('/manuscripts/' + id)
  await router.isReady()
  const wrapper = mount(Manuscript, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('Manuscript PDF inspect surface', () => {
  beforeEach(() => {
    api.mockReset()
    api.mockImplementation((path: string) => Promise.resolve(jsonFor(path)))
  })

  it('shows page spec and figure list for PDF', async () => {
    const wrapper = await mountManuscript('4')
    expect(wrapper.text()).toContain('PDF 页与图表')
    expect(wrapper.text()).toContain('US Letter')
    expect(wrapper.text()).toContain('需 Vision')
    expect(wrapper.text()).toContain('Blurry architecture diagram.')
    expect(wrapper.text()).toContain('稿件 ID')
    expect(wrapper.text()).toContain('版本号')
    expect(api).toHaveBeenCalledWith('/manuscripts/4/versions/1/inspect')
    wrapper.unmount()
  })

  it('keeps extracted text for Markdown and does not fake a page preview', async () => {
    const wrapper = await mountManuscript('5')
    expect(wrapper.text()).toContain('Word and Markdown keep extracted text.')
    expect(wrapper.text()).toContain('不是 PDF，不展示页规格')
    expect(wrapper.text()).not.toContain('PDF 页与图表')
    expect(api).not.toHaveBeenCalledWith(expect.stringContaining('/inspect'))
    wrapper.unmount()
  })
})
