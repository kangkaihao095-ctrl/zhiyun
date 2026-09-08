import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Chat from '../src/views/Chat.vue'
import { CS_MEMORY_MAX_TURNS, chatRequestBody, chatWindow } from '../src/cs-memory.js'

describe('cs-memory', () => {
  it('keeps the last 5 rounds as at most 10 turns', () => {
    const turns = []
    for (let i = 1; i <= 8; i++) {
      turns.push({ role: 'user', content: `U${i}` })
      turns.push({ role: 'assistant', content: `A${i}` })
    }
    const window = chatWindow(turns)
    expect(window).toHaveLength(CS_MEMORY_MAX_TURNS)
    expect(window[0]).toEqual({ role: 'user', content: 'U4' })
    expect(window.at(-1)).toEqual({ role: 'assistant', content: 'A8' })
  })

  it('sends an array of turns, not a single concatenated sentence', () => {
    const body = chatRequestBody([
      { role: 'user', content: '我的额度还剩多少？' },
      { role: 'assistant', content: '还剩 3 额度' },
      { role: 'user', content: '那昨天的订单' }
    ])
    expect(body.messages).toHaveLength(3)
    expect(body.messages.map((m) => m.role)).toEqual(['user', 'assistant', 'user'])
    expect(body.messages.at(-1)?.content).toBe('那昨天的订单')
    expect(JSON.stringify(body)).not.toMatch(/History:\[/)
  })
})

describe('Chat dock memory', () => {
  const bodies: unknown[] = []

  beforeEach(() => {
    bodies.length = 0
    sessionStorage.setItem('token', 'test-token')
    globalThis.fetch = vi.fn(async (_url, init) => {
      bodies.push(JSON.parse(String(init?.body || '{}')))
      const payload = 'event: token\ndata: "还剩 3 额度"\n\nevent: done\ndata: "[DONE]"\n\n'
      return new Response(payload, { headers: { 'Content-Type': 'text/event-stream' } })
    }) as typeof fetch
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('posts prior turns on the second question and never writes chat to storage', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    const wrapper = mount(Chat)
    await wrapper.get('.cs-fab').trigger('click')
    await wrapper.get('input').setValue('我的额度还剩多少？')
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(bodies).toHaveLength(1))
    await vi.waitFor(() => {
      expect((wrapper.get('button[type="submit"]').element as HTMLButtonElement).disabled).toBe(false)
    })
    await wrapper.get('input').setValue('那昨天的订单')
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(bodies).toHaveLength(2))

    const second = bodies[1] as { messages: { role: string; content: string }[] }
    expect(second.messages.some((m) => m.content.includes('额度'))).toBe(true)
    expect(second.messages.at(-1)?.content).toContain('那昨天的订单')
    expect(second.messages.length).toBeGreaterThanOrEqual(3)

    const chatWrites = setItem.mock.calls.filter(([key, value]) => {
      const blob = `${key}\n${value}`
      return blob.includes('那昨天的订单') || blob.includes('额度还剩')
    })
    expect(chatWrites).toHaveLength(0)
    wrapper.unmount()
  })
})
