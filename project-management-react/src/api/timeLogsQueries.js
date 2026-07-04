import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import * as timeLogsApi from './timeLogsApi'

const timeLogsKey = (taskId) => ['tasks', taskId, 'time-logs']

export function useTimeLogs(taskId) {
  return useQuery({
    queryKey: timeLogsKey(taskId),
    queryFn: () => timeLogsApi.fetchTimeLogs(taskId),
    enabled: Boolean(taskId),
  })
}

// taskId фиксируется на хуке, как useCreateSubtask — используется со страницы деталей
// одной конкретной задачи (TaskDetailPage).
export function useCreateTimeLog(taskId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload) => timeLogsApi.createTimeLog(taskId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: timeLogsKey(taskId) })
    },
  })
}

// taskId фиксируется на хуке (тот же скоуп, что и useCreateTimeLog); mutate принимает
// id удаляемой записи.
export function useDeleteTimeLog(taskId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (timeLogId) => timeLogsApi.deleteTimeLog(timeLogId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: timeLogsKey(taskId) })
    },
  })
}
