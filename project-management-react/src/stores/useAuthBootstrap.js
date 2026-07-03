import { useEffect, useState } from 'react'
import { refreshAccessToken } from '../api/client'
import { useAuthStore } from './authStore'

// accessToken не персистится (см. authStore.js), поэтому после перезагрузки страницы
// он всегда null, даже если пользователь залогинен — а refreshToken в localStorage жив.
// Перед первым рендером защищённых страниц пробуем молча обменять refreshToken
// на новый accessToken, чтобы не разлогинивать пользователя на каждый F5.
export function useAuthBootstrap() {
  const [ready, setReady] = useState(false)

  useEffect(() => {
    const { accessToken, refreshToken, setSession, clearSession } = useAuthStore.getState()

    if (accessToken || !refreshToken) {
      setReady(true)
      return
    }

    refreshAccessToken(refreshToken)
      .then((data) => setSession(data))
      .catch(() => clearSession())
      .finally(() => setReady(true))
  }, [])

  return ready
}
