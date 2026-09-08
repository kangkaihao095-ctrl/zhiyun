import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ListPager from '../src/components/ListPager.vue'

describe('ListPager', () => {
  it('emits search and resets to page 1 when page size changes', async () => {
    const wrapper = mount(ListPager, {
      props: { q: '', page: 3, size: 10, total: 40, placeholder: '按标题搜索' }
    })
    expect(wrapper.text()).toContain('共 40 条')
    await wrapper.get('.pager-size select').setValue('5')
    expect(wrapper.emitted('update:size')?.[0]).toEqual([5])
    expect(wrapper.emitted('update:page')?.[0]).toEqual([1])
  })

  it('debounces typing into a search event', async () => {
    vi.useFakeTimers()
    const wrapper = mount(ListPager, { props: { q: '', page: 1, size: 10, total: 8 } })
    const input = wrapper.get('input')
    await input.setValue('ACL')
    expect(wrapper.emitted('update:q')?.[0]).toEqual(['ACL'])
    expect(wrapper.emitted('search')).toBeUndefined()
    await vi.advanceTimersByTimeAsync(280)
    expect(wrapper.emitted('search')).toHaveLength(1)
    await wrapper.get('.btn-ghost').trigger('click')
    expect(wrapper.emitted('search')).toHaveLength(2)
  })

  it('hides paging when the list is empty', () => {
    const wrapper = mount(ListPager, { props: { total: 0, page: 1, size: 10 } })
    expect(wrapper.find('.list-pager').exists()).toBe(false)
  })

  it('defaults to 5 items per page', () => {
    const wrapper = mount(ListPager, { props: { total: 12, page: 1 } })
    expect((wrapper.get('.pager-size select').element as HTMLSelectElement).value).toBe('5')
    expect(wrapper.text()).toContain('第 1 / 3 页')
  })
})
