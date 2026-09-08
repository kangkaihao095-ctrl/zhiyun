import { reactive } from 'vue'

export const toasts = reactive([])
let seq = 0

export function toast(message, type = 'ok', ms = 4800) {
  const id = ++seq
  toasts.push({ id, message, type })
  window.setTimeout(() => dismiss(id), ms)
  return id
}

export function dismiss(id) {
  const i = toasts.findIndex((t) => t.id === id)
  if (i >= 0) {
    toasts.splice(i, 1)
  }
}
