import { afterEach, describe, expect, it, vi } from 'vitest'
import { dismiss, toast, toasts } from '../src/toast.js'

describe('toast', () => {
  afterEach(() => {
    toasts.splice(0, toasts.length)
  })

  it('pushes and auto-dismisses', () => {
    vi.useFakeTimers()
    const id = toast('已到账', 'ok', 1000)
    expect(toasts).toHaveLength(1)
    expect(toasts[0]).toMatchObject({ id, message: '已到账', type: 'ok' })
    vi.advanceTimersByTime(1000)
    expect(toasts).toHaveLength(0)
  })

  it('can be dismissed early', () => {
    vi.useFakeTimers()
    const id = toast('失败', 'err', 5000)
    dismiss(id)
    expect(toasts).toHaveLength(0)
  })
})
