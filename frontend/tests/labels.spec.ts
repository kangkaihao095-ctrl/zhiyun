import { describe, expect, it, vi } from 'vitest'
import {
  copyText,
  formatTime,
  ledgerReason,
  ledgerRelated,
  ledgerIds,
  quotaHint,
  quotaText,
  statusLabel,
  versionStatusLabel,
  yuan
} from '../src/labels.js'

describe('statusLabel', () => {
  it('maps review and billing statuses', () => {
    expect(statusLabel('WAITING_ACCEPT')).toBe('等你确认修改')
    expect(statusLabel('RUNNING')).toBe('正在审校')
    expect(statusLabel('PAID')).toBe('已到账')
  })

  it('falls back to the raw code', () => {
    expect(statusLabel('UNKNOWN')).toBe('UNKNOWN')
  })
})

describe('versionStatusLabel', () => {
  it('labels official as the trunk', () => {
    expect(versionStatusLabel('OFFICIAL')).toBe('正式稿（主干）')
    expect(versionStatusLabel('CANDIDATE')).toBe('候选稿')
  })
})

describe('formatTime', () => {
  it('renders a local timestamp', () => {
    expect(formatTime('2026-09-07T03:04:00+08:00')).toBe('2026-09-07 03:04')
  })

  it('returns a dash for empty or invalid values', () => {
    expect(formatTime('')).toBe('—')
    expect(formatTime('not-a-date')).toBe('—')
  })
})

describe('ledger', () => {
  it('translates known reasons', () => {
    expect(ledgerReason('PURCHASE')).toBe('充值到账')
    expect(ledgerReason('SKILL_FEE')).toBe('技能与提示词服务费')
    expect(ledgerReason('REVIEW_USAGE')).toBe('审校消耗')
  })

  it('links task and order refs', () => {
    expect(ledgerRelated('task-12')).toEqual({ label: '审校任务 12', to: '/reviews/12' })
    expect(ledgerRelated('task-ZYT20260908120100ABCDEF12')).toEqual({
      label: '审校任务 ZYT20260908120100ABCDEF12',
      to: '/reviews/ZYT20260908120100ABCDEF12'
    })
    expect(ledgerRelated('order-ZY1')).toEqual({ label: '订单 ZY1', to: '/orders/ZY1' })
    expect(ledgerRelated('')).toEqual({ label: '', to: '' })
  })

  it('exposes ledger and order ids for copy', () => {
    expect(ledgerIds({ id: 88, refId: 'order-ZY1' })).toEqual({
      ledgerId: '88',
      orderId: 'ZY1',
      taskId: ''
    })
    expect(ledgerIds({ id: 9, refId: 'task-12' })).toEqual({
      ledgerId: '9',
      orderId: '',
      taskId: '12'
    })
  })
})

describe('quota helpers', () => {
  it('formats yuan from cents', () => {
    expect(yuan(32900)).toBe('329')
    expect(yuan(0)).toBe('0')
  })

  it('explains remaining quota', () => {
    expect(quotaText(8)).toBe('8 额度')
    expect(quotaHint(0)).toContain('额度用完了')
    expect(quotaHint(2)).toContain('引用核验')
    expect(quotaHint(14)).toContain('完整审校')
  })
})

describe('copyText', () => {
  it('writes to the clipboard', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    await expect(copyText('ZY123')).resolves.toBe(true)
    expect(writeText).toHaveBeenCalledWith('ZY123')
  })

  it('returns false when clipboard is unavailable', async () => {
    vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } })
    await expect(copyText('ZY123')).resolves.toBe(false)
    await expect(copyText('')).resolves.toBe(false)
  })
})
