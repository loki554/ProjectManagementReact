import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import * as attachmentsApi from './attachmentsApi'

const attachmentsKey = (taskId) => ['tasks', taskId, 'attachments']

export function useAttachments(taskId) {
  return useQuery({
    queryKey: attachmentsKey(taskId),
    queryFn: () => attachmentsApi.fetchAttachments(taskId),
    enabled: Boolean(taskId),
  })
}

// taskId фиксируется на хуке, как useCreateTimeLog — используется со страницы деталей
// одной конкретной задачи (TaskDetailPage).
export function useUploadAttachment(taskId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (file) => attachmentsApi.uploadAttachment(taskId, file),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: attachmentsKey(taskId) })
    },
  })
}

// taskId фиксируется на хуке (тот же скоуп, что и useUploadAttachment); mutate принимает
// id удаляемого вложения.
export function useDeleteAttachment(taskId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (attachmentId) => attachmentsApi.deleteAttachment(attachmentId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: attachmentsKey(taskId) })
    },
  })
}
