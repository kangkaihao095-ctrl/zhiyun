import { readonly, ref, type Ref } from 'vue'

export const THEME_STORAGE_KEY = 'zhiyun-theme'
export type ThemeName = 'light' | 'dark'

const themeRef = ref<ThemeName>('light')
let media: MediaQueryList | null = null
let listening = false

export const theme: Readonly<Ref<ThemeName>> = readonly(themeRef)

function isTheme(value: string | null): value is ThemeName {
  return value === 'light' || value === 'dark'
}

export function readStoredTheme(): ThemeName | null {
  try {
    const value = localStorage.getItem(THEME_STORAGE_KEY)
    return isTheme(value) ? value : null
  } catch {
    return null
  }
}

export function systemTheme(): ThemeName {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return 'light'
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

export function resolveTheme(): ThemeName {
  return readStoredTheme() ?? systemTheme()
}

export function applyTheme(next: ThemeName): void {
  themeRef.value = next
  const root = document.documentElement
  root.setAttribute('data-theme', next)
  root.style.colorScheme = next
}

export function setTheme(next: ThemeName): void {
  try {
    localStorage.setItem(THEME_STORAGE_KEY, next)
  } catch {
    /* private mode / quota */
  }
  applyTheme(next)
}

export function toggleTheme(): ThemeName {
  const next: ThemeName = resolveTheme() === 'dark' ? 'light' : 'dark'
  setTheme(next)
  return next
}

function onSystemChange(): void {
  if (readStoredTheme()) return
  applyTheme(systemTheme())
}

export function bootTheme(): ThemeName {
  const next = resolveTheme()
  applyTheme(next)
  if (listening) return next
  listening = true
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return next
  media = window.matchMedia('(prefers-color-scheme: dark)')
  if (typeof media.addEventListener === 'function') {
    media.addEventListener('change', onSystemChange)
  } else if (typeof media.addListener === 'function') {
    media.addListener(onSystemChange)
  }
  return next
}
