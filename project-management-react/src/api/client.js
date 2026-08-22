import axios from 'axios'
import i18n from '../i18n'
import { useAuthStore } from '../stores/authStore'
import { useToastStore } from '../stores/toastStore'

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

const REFRESH_LOCK_NAME = 'pmtracker-auth-refresh'

let refreshPromise = null

/**
 * Обменивает refresh-токен на новую пару и сразу кладёт её в стор.
 *
 * Экспортируется, чтобы bootstrap-логика при загрузке приложения (см. useAuthBootstrap)
 * могла явно освежить сессию, не дожидаясь первого 401.
 *
 * Обновление обязано быть эксклюзивным, иначе две параллельные попытки предъявят бэкенду
 * один и тот же токен, второй ответят 401 — и с версии 1.6 (detection повторного
 * использования) она погасит вообще все сессии пользователя, посчитав это кражей.
 * Эксклюзивность двухуровневая: refreshPromise схлопывает параллельные вызовы внутри
 * вкладки, Web Locks — между вкладками одного браузера.
 */
export async function refreshSession() {
  if (!refreshPromise) {
    refreshPromise = requestRefreshExclusively().finally(() => {
      refreshPromise = null
    })
  }
  return refreshPromise
}

function requestRefreshExclusively() {
  // navigator.locks нет в небезопасном контексте (http на IP в локальной сети) и в старых
  // браузерах. Тогда остаётся только внутривкладочная защита — это ровно то поведение,
  // которое было до появления замка, деградация мягкая.
  if (!navigator.locks) {
    return requestRefresh()
  }
  return navigator.locks.request(REFRESH_LOCK_NAME, requestRefresh)
}

async function requestRefresh() {
  // Пока мы стояли за замком, соседняя вкладка могла уже сходить за новой парой и записать
  // её в localStorage. Перечитываем стор, чтобы не предъявить бэкенду ротированный токен:
  // для него две живые копии одного токена неотличимы от кражи.
  await Promise.resolve(useAuthStore.persist.rehydrate())

  const { refreshToken } = useAuthStore.getState()
  if (!refreshToken) {
    // Сессию завершили в соседней вкладке, пока мы ждали замок. Обновлять нечего —
    // вызывающий код обработает это как обычную неудачу обновления.
    throw new Error('Refresh token is no longer available')
  }

  // Отдельный axios-вызов, а не apiClient — иначе он тоже пройдёт через этот же
  // interceptor и может зациклиться.
  const response = await axios.post(`${API_BASE_URL}/auth/refresh`, { refreshToken })

  // setSession строго внутри замка: он же пишет новый токен в localStorage, и соседняя
  // вкладка должна увидеть запись до того, как получит замок и прочитает стор.
  useAuthStore.getState().setSession(response.data)
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

    const { accessToken, refreshToken, clearSession } = useAuthStore.getState()
    if (!refreshToken) {
      // accessToken ещё есть — значит для приложения (и пользователя) это первый сигнал,
      // что сессия невалидна, стоит объяснить, почему он вот-вот окажется на /login.
      // Если accessToken уже null, это повторный 401 после того, как сессию уже сбросили
      // (например, параллельный запрос) — тост уже был показан, не дублируем.
      if (accessToken) {
        notifySessionExpired()
      }
      clearSession()
      return Promise.reject(error)
    }

    originalRequest._retry = true

    try {
      // Токен для обмена берётся не отсюда, а из стора внутри refreshSession — к моменту,
      // когда мы получим замок, актуальным может быть уже другой.
      const data = await refreshSession()
      originalRequest.headers.Authorization = `Bearer ${data.accessToken}`
      return apiClient(originalRequest)
    } catch (refreshError) {
      if (useAuthStore.getState().accessToken) {
        notifySessionExpired()
      }
      clearSession()
      return Promise.reject(refreshError)
    }
  },
)

function notifySessionExpired() {
  useToastStore.getState().pushToast(i18n.t('app.sessionExpired'), 'error')
}
