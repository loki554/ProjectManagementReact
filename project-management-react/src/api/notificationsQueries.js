import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRealtimeStore } from '../stores/realtimeStore'
import * as notificationsApi from './notificationsApi'

const NOTIFICATIONS_KEY = ['notifications']
const UNREAD_COUNT_KEY = ['notifications', 'unread-count']
// Опрос раз в 30с — то, чем колокольчик жил до 4.15. Бейдж важнее свежести списка, поэтому
// оба запроса опрашиваются одинаково: список открыт нечасто и ненадолго (дропдаун), лишний
// запрос раз в 30с, пока он открыт, не заметен.
const POLL_INTERVAL_MS = 30000

// Пока живой поток на месте (4.15), уведомление приезжает в ту же секунду, и опрос нужен
// уже не для свежести, а как страховка: поток могли молча съесть прокси, корпоративный
// фильтр или спящая вкладка, и «соединение открыто» не то же самое, что «события доходят».
// Поэтому не false, а редко: поток понижает опрос в статус подстраховки, а не отменяет его.
const RELAXED_POLL_INTERVAL_MS = 300000

function usePollInterval() {
  const connected = useRealtimeStore((state) => state.connected)
  return connected ? RELAXED_POLL_INTERVAL_MS : POLL_INTERVAL_MS
}

export function useUnreadNotificationCount() {
  const interval = usePollInterval()
  return useQuery({
    queryKey: UNREAD_COUNT_KEY,
    queryFn: notificationsApi.fetchUnreadNotificationCount,
    refetchInterval: interval,
  })
}

// enabled — список грузится только пока открыт дропдаун колокольчика; счётчик выше
// опрашивается всегда независимо от него.
export function useNotifications(enabled) {
  const interval = usePollInterval()
  return useQuery({
    queryKey: [...NOTIFICATIONS_KEY, 'list'],
    queryFn: () => notificationsApi.fetchNotifications(0),
    enabled,
    refetchInterval: enabled ? interval : false,
  })
}

export function useMarkNotificationRead() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id) => notificationsApi.markNotificationRead(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: NOTIFICATIONS_KEY })
    },
  })
}

export function useMarkAllNotificationsRead() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: notificationsApi.markAllNotificationsRead,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: NOTIFICATIONS_KEY })
    },
  })
}
