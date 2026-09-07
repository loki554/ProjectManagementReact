import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import * as savedViewsApi from './savedViewsApi'

// Ключ скоупирован проектом, но не пользователем: сервер и так отдаёт только свои
// представления, а смена пользователя чистит кэш целиком (см. authStore).
const savedViewsKey = (projectId) => ['projects', projectId, 'views']

export function useSavedViews(projectId) {
  return useQuery({
    queryKey: savedViewsKey(projectId),
    queryFn: () => savedViewsApi.fetchSavedViews(projectId),
    enabled: Boolean(projectId),
  })
}

export function useCreateSavedView(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload) => savedViewsApi.createSavedView(projectId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: savedViewsKey(projectId) })
    },
  })
}

// projectId фиксируется на хуке (список, который надо перечитать), id представления
// приезжает в mutate — на странице их несколько, и заводить по хуку на каждое незачем.
export function useUpdateSavedView(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ viewId, ...payload }) => savedViewsApi.updateSavedView(viewId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: savedViewsKey(projectId) })
    },
  })
}

export function useDeleteSavedView(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (viewId) => savedViewsApi.deleteSavedView(viewId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: savedViewsKey(projectId) })
    },
  })
}
