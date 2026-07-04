import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import * as tagsApi from './tagsApi'

const tagsKey = (projectId) => ['projects', projectId, 'tags']

export function useTags(projectId) {
  return useQuery({
    queryKey: tagsKey(projectId),
    queryFn: () => tagsApi.fetchTags(projectId),
    enabled: Boolean(projectId),
  })
}

// projectId фиксируется на уровне хука, по аналогии с member-хуками в projectsQueries.js —
// расчёт на страницу настроек тэгов одного конкретного проекта.
export function useCreateTag(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload) => tagsApi.createTag(projectId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: tagsKey(projectId) })
    },
  })
}

export function useUpdateTag(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ tagId, payload }) => tagsApi.updateTag(tagId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: tagsKey(projectId) })
    },
  })
}

export function useDeleteTag(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (tagId) => tagsApi.deleteTag(tagId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: tagsKey(projectId) })
    },
  })
}
