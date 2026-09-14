import { describe, expect, it } from 'vitest'
import { diffLines, hasPatchDiff, patchDiffRows, visibleDiffRows } from '../src/merge-diff.js'

describe('diffLines', () => {
  it('marks replacements as delete then add', () => {
    const rows = diffLines('alpha\nbeta\ngamma', 'alpha\nBETA\ngamma')
    expect(rows).toEqual([
      { type: 'eq', text: 'alpha' },
      { type: 'del', text: 'beta' },
      { type: 'add', text: 'BETA' },
      { type: 'eq', text: 'gamma' }
    ])
  })

  it('handles empty strings', () => {
    expect(diffLines('', 'hi')).toEqual([
      { type: 'del', text: '' },
      { type: 'add', text: 'hi' }
    ])
  })
})

describe('visibleDiffRows', () => {
  it('collapses unchanged lines when onlyChanges is on', () => {
    const rows = diffLines('a\nb\nc\nd', 'a\nB\nc\nd')
    const visible = visibleDiffRows(rows, true)
    expect(visible.some((row) => row.type === 'skip' && row.count === 1)).toBe(true)
    expect(visible.filter((row) => row.type === 'del' || row.type === 'add')).toHaveLength(2)
  })
})

describe('patchDiffRows', () => {
  it('returns delete then add for a sentence replacement', () => {
    const rows = patchDiffRows('see 10.0000/ghost.doi', 'remove the unverified citation')
    expect(hasPatchDiff('see 10.0000/ghost.doi', 'remove the unverified citation')).toBe(true)
    expect(rows).toEqual([
      { type: 'del', text: 'see 10.0000/ghost.doi' },
      { type: 'add', text: 'remove the unverified citation' }
    ])
  })

  it('is empty when there is nothing to compare', () => {
    expect(hasPatchDiff('', '')).toBe(false)
    expect(patchDiffRows('', '')).toEqual([])
    expect(patchDiffRows('same', 'same')).toEqual([])
  })
})
