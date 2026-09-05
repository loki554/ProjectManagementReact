import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import * as notificationSettingsApi from './notificationSettingsApi'

const SETTINGS_KEY = ['notification-settings']

export function useNotificationSettings() {
  return useQuery({
    queryKey: SETTINGS_KEY,
    queryFn: notificationSettingsApi.fetchNotificationSettings,
  })
}

export function useUpdateNotificationSettings() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: notificationSettingsApi.updateNotificationSettings,
    // Ответ — это уже сохранённое состояние целиком, поэтому кладём его в кэш вместо
    // инвалидации: лишний GET здесь ничего нового не узнает.
    onSuccess: (settings) => {
      queryClient.setQueryData(SETTINGS_KEY, settings)
    },
  })
}

// Отписка живёт вне этого кэша: страница /unsubscribe открывается без входа, и настроек
// в кэше у неё нет и быть не может.
export function useUnsubscribe(token) {
  return useMutation({
    mutationFn: () => notificationSettingsApi.unsubscribeFromNotificationEmails(token),
  })
}
