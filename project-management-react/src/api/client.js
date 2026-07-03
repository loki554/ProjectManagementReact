import axios from 'axios'
import { useAuthStore } from '../stores/authStore'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api'

export const apiClient = axios.create({
  baseURL: API_BASE_URL,
})

// Подставляем актуальный access-токен из стора в каждый запрос.
// useAuthStore.getState() читает состояние напрямую, без хука — так можно
// делать вне React-компонентов, в том числе прямо здесь, в interceptor'е.
apiClient.interceptors.request.use((config) => {
  const { accessToken } = useAuthStore.getState()
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`
  }
  return config
})

// Эндпоинты, для которых 401 не означает "токен протух, надо освежить":
// либо это сам refresh (чтобы не зациклиться), либо запрос вообще не подразумевает
// авторизации, и 401/403 там — это осмысленный ответ (неверный пароль и т.п.).
const AUTH_ENDPOINTS_WITHOUT_RETRY = [
  '/auth/login',
  '/auth/register',
  '/auth/refresh',
  '/auth/verify-email',
  '/auth/resend-verification',
]

let refreshPromise = null

// Экспортируется отдельно, чтобы bootstrap-логика при загрузке приложения
// (см. useAuthBootstrap) могла явно освежить сессию, не дожидаясь первого 401.
export async function refreshAccessToken(refreshToken) {
  // Отдельный axios-вызов, а не apiClient — иначе он тоже пройдёт через этот же
  // interceptor и может зациклиться.
  const response = await axios.post(`${API_BASE_URL}/auth/refresh`, { refreshToken })
  return response.data
}

apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config
    const status = error.response?.status

    const isExemptEndpoint = AUTH_ENDPOINTS_WITHOUT_RETRY.some((path) =>
      originalRequest?.url?.includes(path),
    )

    if (status !== 401 || !originalRequest || originalRequest._retry || isExemptEndpoint) {
      return Promise.reject(error)
    }

    const { refreshToken, setSession, clearSession } = useAuthStore.getState()
    if (!refreshToken) {
      clearSession()
      return Promise.reject(error)
    }

    originalRequest._retry = true

    try {
      // Если несколько запросов словили 401 одновременно, обновляем токен один раз
      // на всех, а не по разу на каждый запрос.
      if (!refreshPromise) {
        refreshPromise = refreshAccessToken(refreshToken).finally(() => {
          refreshPromise = null
        })
      }
      const data = await refreshPromise
      setSession(data)
      originalRequest.headers.Authorization = `Bearer ${data.accessToken}`
      return apiClient(originalRequest)
    } catch (refreshError) {
      clearSession()
      return Promise.reject(refreshError)
    }
  },
)
