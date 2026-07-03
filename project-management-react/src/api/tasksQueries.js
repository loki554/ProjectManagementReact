import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import * as tasksApi from './tasksApi'

const tasksKey = (projectId) => ['projects', projectId, 'tasks']
const taskKey = (taskId) => ['tasks', taskId]
const subtasksKey = (taskId) => ['tasks', taskId, 'subtasks']

export function useTasks(projectId, filters = {}) {
  return useQuery({
    queryKey: [...tasksKey(projectId), filters],
    queryFn: () => tasksApi.fetchTasks(projectId, filters),
    enabled: Boolean(projectId),
  })
}

export function useTask(taskId) {
  return useQuery({
    queryKey: taskKey(taskId),
    queryFn: () => tasksApi.fetchTask(taskId),
    enabled: Boolean(taskId),
  })
}

// projectId фиксируется на хуке, как в useInviteMember — предполагается использование
// со страницы, скоупированной на один проект (список задач/канбан).
export function useCreateTask(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload) => tasksApi.createTask(projectId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: tasksKey(projectId) })
    },
  })
}

// taskId фиксируется на хуке, как в useUpdateProject — предполагается использование
// со страницы деталей одной конкретной задачи (TaskDetailPage).
export function useUpdateTask(taskId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload) => tasksApi.updateTask(taskId, payload),
    onSuccess: (data) => {
      queryClient.setQueryData(taskKey(taskId), data)
      queryClient.invalidateQueries({ queryKey: tasksKey(data.projectId) })
      if (data.parentTaskId) {
        queryClient.invalidateQueries({ queryKey: subtasksKey(data.parentTaskId) })
      }
    },
  })
}

// projectId фиксируется на хуке (как useDeleteProject фиксирует список, из которого удаляют).
// mutate принимает { taskId, parentTaskId? } — parentTaskId нужен, только если удаляемая
// задача сама является подзадачей, чтобы обновить кэш списка подзадач её родителя.
export function useDeleteTask(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ taskId }) => tasksApi.deleteTask(taskId),
    onSuccess: (_data, { taskId, parentTaskId }) => {
      queryClient.invalidateQueries({ queryKey: tasksKey(projectId) })
      queryClient.removeQueries({ queryKey: taskKey(taskId) })
      queryClient.removeQueries({ queryKey: subtasksKey(taskId) })
      if (parentTaskId) {
        queryClient.invalidateQueries({ queryKey: subtasksKey(parentTaskId) })
      }
    },
  })
}

export function useSubtasks(taskId) {
  return useQuery({
    queryKey: subtasksKey(taskId),
    queryFn: () => tasksApi.fetchSubtasks(taskId),
    enabled: Boolean(taskId),
  })
}

// parentTaskId фиксируется на хуке — используется из TaskDetailPage конкретной родительской задачи.
export function useCreateSubtask(parentTaskId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload) => tasksApi.createSubtask(parentTaskId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: subtasksKey(parentTaskId) })
    },
  })
}
