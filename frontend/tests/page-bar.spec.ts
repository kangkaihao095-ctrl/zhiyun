import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PageBar from '../src/components/PageBar.vue'

describe('PageBar', () => {
  it('hides when the list fits one page', () => {
    const wrapper = mount(PageBar, { props: { page: 1, size: 5, total: 5 } })
    expect(wrapper.find('.list-pager').exists()).toBe(false)
    wrapper.unmount()
  })

  it('pages a long finding list by 5', async () => {
    const wrapper = mount(PageBar, { props: { page: 1, size: 5, total: 12 } })
    expect(wrapper.text()).toContain('共 12 条')
    expect(wrapper.text()).toContain('第 1 / 3 页')
    const next = wrapper.findAll('button').find((b) => b.text() === '下一页')
    expect(next).toBeTruthy()
    await next!.trigger('click')
    expect(wrapper.emitted('update:page')?.[0]).toEqual([2])
    wrapper.unmount()
  })
})
