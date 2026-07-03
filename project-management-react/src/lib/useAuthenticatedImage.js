import { useEffect, useState } from 'react'
import { apiClient } from '../api/client'

// Обычный <img src="/api/users/{id}/avatar"> не сработает: браузер не приложит
// Authorization-заголовок к запросу картинки, а эндпоинт требует авторизации
// (см. UserController.getAvatar на бэке). Поэтому тянем байты через apiClient
// (с уже подключённым interceptor'ом) и превращаем в blob-URL для <img>.
export function useAuthenticatedImage(url) {
  const [objectUrl, setObjectUrl] = useState(null)

  useEffect(() => {
    if (!url) {
      setObjectUrl(null)
      return
    }

    let cancelled = false
    let createdUrl = null

    apiClient.get(url, { responseType: 'blob' }).then((res) => {
      if (cancelled) return
      createdUrl = URL.createObjectURL(res.data)
      setObjectUrl(createdUrl)
    })

    return () => {
      cancelled = true
      if (createdUrl) {
        URL.revokeObjectURL(createdUrl)
      }
    }
  }, [url])

  return objectUrl
}
