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
    // Намеренно НЕ используем removeQueries(taskKey(taskId))/removeQueries(subtasksKey(taskId)):
    // TaskDetailPage, с которой обычно вызывается удаление, ещё смонтирована в момент onSuccess
    // (навигация происходит асинхронно) и подписана на эти же ключи через useTask/useSubtasks —
    // removeQueries на активный запрос заставляет react-query тут же перезапросить удалённую
    // задачу и словить 404 в консоли прямо перед уходом со страницы. Инвалидации родительских
    // списков достаточно: на удалённый id больше никто не подписывается после навигации прочь.
    onSuccess: (_data, { parentTaskId }) => {
      queryClient.invalidateQueries({ queryKey: tasksKey(projectId) })
      if (parentTaskId) {
        queryClient.invalidateQueries({ queryKey: subtasksKey(parentTaskId) })
      }
    },
  })
}

// Клиентское зеркало backend-алгоритма пересчёта position (TaskService.updateStatus, 5.1.2):
// та же "колонка" — top-level задачи одного статуса — перенумеровывается 0..n-1 после
// вставки перемещённой задачи на targetIndex. Работает над плоским кэшированным списком
// без явной группировки: сортировка внутри каждого статуса определяется порядком добавления
// в возвращаемый массив, а группировка по статусу на странице канбана (фильтром по task.status)
// сохраняет этот относительный порядок независимо от чередования с другими статусами.
function reorderTasksOptimistically(tasks, { taskId, status: newStatus, position: targetIndex }) {
  const moved = tasks.find((task) => task.id === taskId)
  if (!moved) {
    return tasks
  }
  const oldStatus = moved.status
  const others = tasks.filter((task) => task.id !== taskId)

  if (oldStatus === newStatus) {
    const column = others.filter((task) => task.status === newStatus).sort((a, b) => a.position - b.position)
    const rest = others.filter((task) => task.status !== newStatus)
    column.splice(targetIndex, 0, moved)
    return [...rest, ...column.map((task, index) => ({ ...task, position: index }))]
  }

  const oldColumn = others.filter((task) => task.status === oldStatus).sort((a, b) => a.position - b.position)
  const newColumn = others.filter((task) => task.status === newStatus).sort((a, b) => a.position - b.position)
  const untouched = others.filter((task) => task.status !== oldStatus && task.status !== newStatus)

  newColumn.splice(targetIndex, 0, { ...moved, status: newStatus })

  return [
    ...untouched,
    ...oldColumn.map((task, index) => ({ ...task, position: index })),
    ...newColumn.map((task, index) => ({ ...task, position: index })),
  ]
}

// projectId фиксируется на хуке, как в useCreateTask — используется с канбан-страницы,
// скоупированной на один проект. Optimistic update: onMutate сразу переставляет задачу
// в кэше (см. reorderTasksOptimistically), onError откатывает к снимку "до", onSettled
// сверяет с сервером — сервер остаётся источником истины для итогового position (см. §6).
export function useUpdateTaskStatus(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ taskId, status, position }) => tasksApi.updateTaskStatus(taskId, { status, position }),
    onMutate: async ({ taskId, status, position }) => {
      await queryClient.cancelQueries({ queryKey: tasksKey(projectId) })
      const previous = queryClient.getQueriesData({ queryKey: tasksKey(projectId) })
      queryClient.setQueriesData({ queryKey: tasksKey(projectId) }, (old) =>
        old ? reorderTasksOptimistically(old, { taskId, status, position }) : old,
      )
      return { previous }
    },
    onError: (_error, _variables, context) => {
      context?.previous?.forEach(([key, data]) => {
        queryClient.setQueryData(key, data)
      })
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: tasksKey(projectId) })
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
