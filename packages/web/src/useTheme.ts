import { useEffect, useState } from 'react'

export type ThemeMode = 'system' | 'light' | 'dark'
export const THEME_MODES: ThemeMode[] = ['system', 'light', 'dark']
const key = 'meshboard.theme'
const query = '(prefers-color-scheme: dark)'

export function loadThemeMode(): ThemeMode {
  try { const saved = localStorage.getItem(key); return saved === 'light' || saved === 'dark' ? saved : 'system' }
  catch { return 'system' }
}

/** Local view preference only; it never touches board state. */
export function useTheme() {
  const [mode, setMode] = useState<ThemeMode>(loadThemeMode)
  const [systemDark, setSystemDark] = useState(() => matchMedia(query).matches)
  useEffect(() => {
    const system = matchMedia(query)
    const update = () => setSystemDark(system.matches)
    update()
    system.addEventListener('change', update)
    return () => system.removeEventListener('change', update)
  }, [])
  const dark = mode === 'dark' || (mode === 'system' && systemDark)
  useEffect(() => {
    document.documentElement.dataset.theme = dark ? 'dark' : 'light'
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', dark ? '#202922' : '#f8f9f6')
  }, [dark])
  useEffect(() => {
    try { localStorage.setItem(key, mode) } catch { /* Theme still works when storage is unavailable. */ }
  }, [mode])
  return { mode, setMode, dark }
}
