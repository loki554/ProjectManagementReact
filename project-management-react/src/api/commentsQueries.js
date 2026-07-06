import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import * as commentsApi from './commentsApi'

// Ключ включает sort (сервер сортирует сам), поэтому мутации инвалидируют
// 3-элементный префикс — иначе неактивное направление сортировки протухнет.
const commentsKeyPrefix = (taskId) => ['tasks', taskId, 'comments']

export function useComments(taskId, sort) {
  return useQuery({
    queryKey: [...commentsKeyPrefix(taskId), sort],
    queryFn: () => commentsApi.fetchComments(taskId, sort),
    enabled: Boolean(taskId),
  })
}

// taskId/projectId фиксируются на хуке, как useCreateTimeLog — используется со страницы
// просмотра одной задачи. projectId нужен для инвалидации ленты активности: вкладка
// «Активность» видна на том же экране, comment_added должен появиться без перезагрузки.
export function useCreateComment(taskId, projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload) => commentsApi.createComment(taskId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: commentsKeyPrefix(taskId) })
      queryClient.invalidateQueries({ queryKey: ['projects', projectId, 'activity'] })
    },
  })
}

// taskId фиксируется на хуке (тот же скоуп, что useCreateComment); mutate принимает
// id удаляемого комментария.
export function useDeleteComment(taskId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (commentId) => commentsApi.deleteComment(commentId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: commentsKeyPrefix(taskId) })
    },
  })
}
