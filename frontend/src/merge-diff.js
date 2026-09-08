/** @typedef {{ type: 'eq' | 'del' | 'add' | 'skip', text: string, count?: number }} DiffRow */

/**
 * @param {string} [a]
 * @param {string} [b]
 * @returns {DiffRow[]}
 */
export function diffLines(a, b) {
  const left = String(a || '').split('\n')
  const right = String(b || '').split('\n')
  const n = left.length
  const m = right.length
  const dp = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = left[i] === right[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }
  const out = []
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (left[i] === right[j]) {
      out.push({ type: 'eq', text: left[i] })
      i++
      j++
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      out.push({ type: 'del', text: left[i++] })
    } else {
      out.push({ type: 'add', text: right[j++] })
    }
  }
  while (i < n) out.push({ type: 'del', text: left[i++] })
  while (j < m) out.push({ type: 'add', text: right[j++] })
  return out
}

/**
 * @param {DiffRow[]} rows
 * @param {boolean} onlyChanges
 * @returns {DiffRow[]}
 */
export function visibleDiffRows(rows, onlyChanges) {
  if (!onlyChanges) return rows
  const out = []
  let skip = 0
  const flush = () => {
    if (skip) {
      out.push({ type: 'skip', count: skip, text: '' })
      skip = 0
    }
  }
  for (const row of rows) {
    if (row.type === 'eq') {
      skip++
    } else {
      flush()
      out.push(row)
    }
  }
  flush()
  return out
}
