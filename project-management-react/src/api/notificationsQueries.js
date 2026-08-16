import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import * as notificationsApi from './notificationsApi'

const NOTIFICATIONS_KEY = ['notifications']
const UNREAD_COUNT_KEY = ['notifications', 'unread-count']
// Нет WebSocket/SSE (вне скоупа MVP) — поллинг раз в 30с. Бейдж на колокольчике важнее
// свежести списка, поэтому оба запроса опрашиваются одинаково: список открыт нечасто и
// ненадолго (дропдаун), лишний запрос раз в 30с, пока он открыт, не заметен.
const POLL_INTERVAL_MS = 30000

export function useUnreadNotificationCount() {
  return useQuery({
    queryKey: UNREAD_COUNT_KEY,
    queryFn: notificationsApi.fetchUnreadNotificationCount,
    refetchInterval: POLL_INTERVAL_MS,
  })
}

// enabled — список грузится только пока открыт дропдаун колокольчика; счётчик выше
// опрашивается всегда независимо от него.
export function useNotifications(enabled) {
  return useQuery({
    queryKey: [...NOTIFICATIONS_KEY, 'list'],
    queryFn: () => notificationsApi.fetchNotifications(0),
    enabled,
    refetchInterval: enabled ? POLL_INTERVAL_MS : false,
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
