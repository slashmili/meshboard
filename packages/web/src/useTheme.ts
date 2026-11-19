import { useEffect, useState } from 'react'

export type ThemeMode = 'system' | 'light' | 'dark'
const key = 'meshboard.theme'
export function useTheme() {
  const [mode, setMode] = useState<ThemeMode>(() => {
    try { const saved = localStorage.getItem(key); return saved === 'light' || saved === 'dark' ? saved : 'system' }
    catch { return 'system' }
  })
  useEffect(() => {
    const system = matchMedia('(prefers-color-scheme: dark)')
    const apply = () => { document.documentElement.dataset.theme = mode === 'system' ? (system.matches ? 'dark' : 'light') : mode }
    apply()
    system.addEventListener('change', apply)
    try { localStorage.setItem(key, mode) } catch { /* Theme still works when storage is unavailable. */ }
    return () => system.removeEventListener('change', apply)
  }, [mode])
  return { mode, setMode }
}
