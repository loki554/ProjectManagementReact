import { useEffect, useRef, useState } from 'react'
import { refreshAccessToken } from '../api/client'
import i18n from '../i18n'
import { useAuthStore } from './authStore'
import { useToastStore } from './toastStore'

// accessToken не персистится (см. authStore.js), поэтому после перезагрузки страницы
// он всегда null, даже если пользователь залогинен — а refreshToken в localStorage жив.
// Перед первым рендером защищённых страниц пробуем молча обменять refreshToken
// на новый accessToken, чтобы не разлогинивать пользователя на каждый F5.
export function useAuthBootstrap() {
  const [ready, setReady] = useState(false)
  // React.StrictMode в dev дважды подряд монтирует эффект без ожидания между вызовами —
  // без этого guard'а обе синхронные инвокации читают ещё не обновлённое состояние стора
  // и обе уходят в refreshAccessToken/clearSession, из-за чего пользователь при истёкшей
  // сессии видел два одинаковых тоста вместо одного (найдено в браузерном смоук-тесте, см.
  // Phase 8). Ref переживает cycle mount→unmount→mount из StrictMode (это один и тот же
  // fiber), поэтому вторая инвокация видит startedRef.current уже true и ничего не делает.
  const startedRef = useRef(false)

  useEffect(() => {
    if (startedRef.current) {
      return
    }
    startedRef.current = true

    const { accessToken, refreshToken, setSession, clearSession } = useAuthStore.getState()

    if (accessToken || !refreshToken) {
      setReady(true)
      return
    }

    refreshAccessToken(refreshToken)
      .then((data) => setSession(data))
      .catch(() => {
        clearSession()
        // refreshToken был (см. guard выше), значит пользователь считал себя залогиненным —
        // без этого тоста он молча попадает на /login без единого объяснения, почему.
        useToastStore.getState().pushToast(i18n.t('app.sessionExpired'), 'error')
      })
      .finally(() => setReady(true))
  }, [])

  return ready
}
