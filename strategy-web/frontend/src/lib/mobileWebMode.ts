import { useEffect, useState } from 'react'

const MOBILE_PATH = '/m'
const MOBILE_QUERY = 'mobile'
const MOBILE_LS_KEY = 'z-strategy-mobile-web'

function normalizePath(pathname: string): string {
  const p = pathname.replace(/\/$/, '') || '/'
  return p
}

/** URL /m, ?mobile=1 или узкий экран (Tailscale с телефона без /m). */
export function detectMobileWebMode(): boolean {
  if (typeof window === 'undefined') return false

  const path = normalizePath(window.location.pathname)
  if (path === MOBILE_PATH || path.endsWith(MOBILE_PATH)) return true
  if (new URLSearchParams(window.location.search).get(MOBILE_QUERY) === '1') return true
  if (localStorage.getItem(MOBILE_LS_KEY) === '1') return true

  const narrow = window.matchMedia('(max-width: 768px)').matches
  const touch =
    window.matchMedia('(pointer: coarse)').matches ||
    navigator.maxTouchPoints > 0
  return narrow && touch
}

/** На телефоне без /m — перейти на /m (сохранить query). */
export function redirectToMobilePathIfNeeded(): void {
  if (typeof window === 'undefined') return

  const path = normalizePath(window.location.pathname)
  if (path === MOBILE_PATH || path.endsWith(MOBILE_PATH)) {
    localStorage.setItem(MOBILE_LS_KEY, '1')
    return
  }
  if (new URLSearchParams(window.location.search).get(MOBILE_QUERY) === '1') {
    localStorage.setItem(MOBILE_LS_KEY, '1')
    return
  }

  const narrow = window.matchMedia('(max-width: 768px)').matches
  const touch =
    window.matchMedia('(pointer: coarse)').matches ||
    navigator.maxTouchPoints > 0
  if (!narrow || !touch) return

  localStorage.setItem(MOBILE_LS_KEY, '1')
  const q = window.location.search
  const hash = window.location.hash
  window.location.replace(`${MOBILE_PATH}${q}${hash}`)
}

export function isMobileWebMode(): boolean {
  return detectMobileWebMode()
}

export function useMobileWebMode(): boolean {
  const [mobile, setMobile] = useState(() => detectMobileWebMode())

  useEffect(() => {
    redirectToMobilePathIfNeeded()
    setMobile(detectMobileWebMode())

    const onResize = () => setMobile(detectMobileWebMode())
    window.addEventListener('resize', onResize)
    window.addEventListener('popstate', onResize)
    return () => {
      window.removeEventListener('resize', onResize)
      window.removeEventListener('popstate', onResize)
    }
  }, [])

  return mobile
}
