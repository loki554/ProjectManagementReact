import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import * as projectNotificationsApi from './projectNotificationsApi'

// Под ['projects', id] сознательно: живые обновления (4.15) инвалидируют кэш именно этим
// префиксом, и настройка, лежащая в стороне, молча оставалась бы несвежей.
const modeKey = (projectId) => ['projects', projectId, 'notification-mode']

export function useProjectNotificationMode(projectId) {
  return useQuery({
    queryKey: modeKey(projectId),
    queryFn: () => projectNotificationsApi.fetchProjectNotificationMode(projectId),
    enabled: Boolean(projectId),
  })
}

// Оптимистично, как звезда: переключатель должен отвечать мгновенно — это личная настройка,
// у которой нет ни валидации, ни конфликтов, и единственная причина ждать сервера здесь —
// это ошибка сети, при которой мы откатываемся к снапшоту.
export function useSetProjectNotificationMode(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (mode) => projectNotificationsApi.setProjectNotificationMode(projectId, mode),
    onMutate: async (mode) => {
      await queryClient.cancelQueries({ queryKey: modeKey(projectId) })
      const previous = queryClient.getQueryData(modeKey(projectId))
      queryClient.setQueryData(modeKey(projectId), { mode })
      return { previous }
    },
    onError: (_error, _mode, context) => {
      if (context?.previous) {
        queryClient.setQueryData(modeKey(projectId), context.previous)
      }
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: modeKey(projectId) })
    },
  })
}
