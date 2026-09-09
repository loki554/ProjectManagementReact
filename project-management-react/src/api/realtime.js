import { useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { readEventStream } from '../lib/eventStream'
import { useAuthStore } from '../stores/authStore'
import { useRealtimeStore } from '../stores/realtimeStore'
import { refreshSession } from './client'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api'
const STREAM_URL = `${API_BASE_URL}/realtime/stream`

/**
 * Пауза перед попыткой переподключения, по номеру подряд идущей неудачи. Последнее
 * значение действует и дальше: сервер, лежащий десять минут, не должен получать запрос
 * каждые полсекунды от каждой открытой вкладки.
 */
const RECONNECT_DELAYS_MS = [1000, 2000, 5000, 15000, 30000]

/**
 * Насколько подождать, если сервер сказал «слишком много потоков» (429). Отдельно от
 * лестницы выше: это не сбой, а лимит на число вкладок, и он не рассосётся за секунду —
 * он рассосётся, когда человек закроет лишнюю вкладку.
 */
const TOO_MANY_STREAMS_DELAY_MS = 60000

/**
 * Сколько копим события, прежде чем сбросить кэши. Одно действие пользователя — это
 * обычно несколько записей в ленте (сменил статус и исполнителя — уже две), а массовая
 * правка (4.6) их даёт по одной на задачу. Без окна каждая из них означала бы отдельный
 * круг перезапросов.
 */
const COALESCE_MS = 300

/**
 * Какие ключи кэша протухли от пришедших событий.
 *
 * Ключи нарочно крупные. Точечная инвалидация («поменялась задача T — перечитать только
 * её») выглядит бережнее, но требует держать здесь карту «событие → все запросы, на
 * которые оно влияет», а карта эта заведомо неполная: смена статуса задачи двигает и
 * доску, и список, и счётчики спринта, и дашборд, и ленту активности. Забытая строчка в
 * такой карте не ломается, а просто оставляет один экран несвежим — то есть ровно ту
 * проблему, ради которой всё и делалось.
 *
 * Крупные ключи ничего не стоят, потому что invalidateQueries перезапрашивает только те
 * запросы, которые кто-то сейчас наблюдает. «Протухло всё про проект P» на странице доски
 * означает один запрос доски, а на странице настроек — ни одного.
 *
 * Экспортируется отдельно от хука: это единственное место с логикой, и проверять её
 * удобнее без потока, React и таймеров.
 */
export function collectInvalidations(messages) {
  const projectIds = new Set()
  let allProjects = false
  let anyTask = false
  let notifications = false

  for (const message of messages) {
    if (message.scope === 'notification') {
      notifications = true
    }
    if (message.projectId) {
      projectIds.add(message.projectId)
    }
    // Правки самого проекта и его состава меняют и список проектов, и проект, найденный по
    // слагу, — а эти ключи лежат не под ['projects', id], а рядом с ним.
    if (message.type?.startsWith('project_') || message.type?.startsWith('member_')) {
      allProjects = true
    }
    // ['tasks'] — это и одна задача, и её подзадачи, и кросс-проектный список «мои
    // активные»: назначение задачи в чужом проекте меняет мой список ровно так же, как
    // в своём.
    if (message.taskId || message.type?.startsWith('task_') || message.type?.startsWith('comment_')) {
      anyTask = true
    }
  }

  const keys = []
  if (notifications) {
    keys.push(['notifications'])
  }
  if (allProjects) {
    keys.push(['projects'])
  } else {
    for (const projectId of projectIds) {
      keys.push(['projects', projectId])
    }
  }
  if (anyTask) {
    keys.push(['tasks'])
  }
  return keys
}

/**
 * Держит открытым поток изменений и сбрасывает по нему кэши react-query (4.15).
 *
 * Вызывается один раз на всё приложение (см. App): поток один на вкладку, а не на страницу.
 * Подписка на конкретный проект отсутствует намеренно — колокольчик и «мои задачи» видны
 * на любой странице, и переоткрывать соединение на каждый переход между проектами значило
 * бы платить рукопожатием за навигацию.
 *
 * Своё эхо игнорируется по actorId. Правку, которую сделал сам, вкладка уже применила
 * (мутация инвалидирует кэш в onSuccess), и второй круг перезапросов через 300 мс ничего
 * не добавит — а платили бы его все и на каждое действие. Цена решения — вторая вкладка
 * того же человека, которая своего же изменения по потоку не увидит; она увидит его при
 * возвращении фокуса (refetchOnWindowFocus), то есть ровно тогда, когда на неё посмотрят.
 */
export function useRealtimeUpdates() {
  const queryClient = useQueryClient()
  // Boolean, а не сам токен: иначе каждое плановое обновление пары токенов (раз в 15 минут)
  // пересоздавало бы эффект и рвало рабочее соединение. Токен читается из стора в момент
  // подключения — там он всегда актуален.
  const authenticated = useAuthStore((state) => Boolean(state.accessToken))
  const currentUserId = useAuthStore((state) => state.user?.id)

  useEffect(() => {
    const setConnected = useRealtimeStore.getState().setConnected
    if (!authenticated) {
      setConnected(false)
      return undefined
    }

    const controller = new AbortController()
    let stopped = false
    let failures = 0
    let pending = []
    let flushTimer = null
    let reconnectTimer = null

    function flush() {
      flushTimer = null
      const messages = pending
      pending = []
      for (const key of collectInvalidations(messages)) {
        queryClient.invalidateQueries({ queryKey: key })
      }
    }

    function handleEvent(event) {
      if (event.name !== 'change') {
        return
      }
      let message
      try {
        message = JSON.parse(event.data)
      } catch {
        // Кадр, который мы не понимаем, — это либо мусор в канале, либо сервер новее
        // клиента. И то и другое лечится обновлением страницы, а не падением потока.
        return
      }
      if (message.actorId && message.actorId === currentUserId) {
        return
      }
      pending.push(message)
      if (flushTimer === null) {
        flushTimer = setTimeout(flush, COALESCE_MS)
      }
    }

    async function run() {
      while (!stopped) {
        let delay = RECONNECT_DELAYS_MS[Math.min(failures, RECONNECT_DELAYS_MS.length - 1)]
        try {
          await readEventStream(STREAM_URL, {
            token: useAuthStore.getState().accessToken,
            signal: controller.signal,
            onOpen: () => {
              failures = 0
              setConnected(true)
            },
            onEvent: handleEvent,
          })
          // Поток закончился без ошибки — это штатный конец жизни соединения по TTL
          // сервера (см. RealtimeConnectionRegistry). Переподключаемся по той же лестнице:
          // после успешного подключения failures обнулён, то есть пауза минимальная.
          delay = RECONNECT_DELAYS_MS[Math.min(failures, RECONNECT_DELAYS_MS.length - 1)]
        } catch (error) {
          if (controller.signal.aborted) {
            return
          }
          if (error?.status === 401) {
            // Access-токен протух, пока висело соединение. Обновляем пару здесь же и
            // пробуем снова сразу: пользователь ничего не заметит. Если обновить не
            // удалось — сессии больше нет, и её обрыв разберёт client.js, а нам
            // остаётся только уйти.
            try {
              await refreshSession()
              failures = 0
              setConnected(false)
              continue
            } catch {
              setConnected(false)
              return
            }
          }
          if (error?.status === 429) {
            failures = RECONNECT_DELAYS_MS.length
            delay = TOO_MANY_STREAMS_DELAY_MS
          } else {
            failures += 1
            delay = RECONNECT_DELAYS_MS[Math.min(failures - 1, RECONNECT_DELAYS_MS.length - 1)]
          }
        }

        setConnected(false)
        if (stopped) {
          return
        }
        // Пауза обязана прерываться вместе с эффектом: без реакции на abort размонтирование
        // во время паузы оставляло бы этот цикл висеть на промисе, который уже никогда не
        // разрешится, вместе со всем замыканием.
        await new Promise((resolve) => {
          reconnectTimer = setTimeout(resolve, delay)
          controller.signal.addEventListener(
            'abort',
            () => {
              clearTimeout(reconnectTimer)
              resolve()
            },
            { once: true },
          )
        })
      }
    }

    run()

    return () => {
      stopped = true
      controller.abort()
      clearTimeout(flushTimer)
      clearTimeout(reconnectTimer)
      setConnected(false)
    }
  }, [authenticated, currentUserId, queryClient])
}
