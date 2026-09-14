import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import SearchPager from '../src/components/SearchPager.vue'

describe('SearchPager', () => {
  it('pages and searches a compact list', async () => {
    const wrapper = mount(SearchPager, {
      props: { q: '', page: 1, size: 9, total: 21, placeholder: '搜索实验室名' }
    })
    expect(wrapper.text()).toContain('21 条')
    expect(wrapper.text()).toContain('第 1 / 3 页')
    const next = wrapper.findAll('button').find((b) => b.text() === '下一页')
    expect(next).toBeTruthy()
    await next!.trigger('click')
    expect(wrapper.emitted('update:page')?.[0]).toEqual([2])
    await wrapper.get('input').setValue('光子')
    expect(wrapper.emitted('update:q')?.[0]).toEqual(['光子'])
  })
})
