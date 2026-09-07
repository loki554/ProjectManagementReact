import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import * as dependenciesApi from './dependenciesApi'
import { fetchTaskByNumber } from './tasksApi'

const dependenciesKey = (taskId) => ['tasks', taskId, 'dependencies']

export function useTaskDependencies(taskId) {
  return useQuery({
    queryKey: dependenciesKey(taskId),
    queryFn: () => dependenciesApi.fetchDependencies(taskId),
    enabled: Boolean(taskId),
  })
}

// Правка связи меняет не только панель зависимостей: у одной из двух задач меняется
// openBlockerCount, а он приезжает в каждом ответе о задаче — и в карточке доски, и в
// строке таблицы. Поэтому инвалидируются обе карточки, обе панели и весь префикс задач
// проекта разом.
function invalidateAfterLinkChange(queryClient, { projectId, taskId, otherTaskId }) {
  for (const id of [taskId, otherTaskId]) {
    queryClient.invalidateQueries({ queryKey: ['tasks', id] })
  }
  queryClient.invalidateQueries({ queryKey: ['projects', projectId, 'tasks'] })
}

/**
 * Добавление связи с любой из двух сторон панели.
 *
 * mutate принимает { taskNumber, direction }: 'blockedBy' — «эту задачу блокирует #N»,
 * 'blocks' — «эта задача блокирует #N». Номер, а не id: именно номер человек видит на
 * карточке и в адресной строке, а в id он разрешается тем же by-number, ради читаемых
 * URL и заведённым. Несуществующий номер приезжает обратно обычным TASK_NOT_FOUND —
 * отдельного текста «нет такой задачи» не требуется.
 */
export function useAddDependency(projectId, taskId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ taskNumber, direction }) => {
      const other = await fetchTaskByNumber(projectId, taskNumber)
      if (direction === 'blocks') {
        await dependenciesApi.addDependency(other.id, taskId)
      } else {
        await dependenciesApi.addDependency(taskId, other.id)
      }
      return other.id
    },
    onSuccess: (otherTaskId) => {
      invalidateAfterLinkChange(queryClient, { projectId, taskId, otherTaskId })
    },
  })
}

/**
 * Снятие связи с любой из двух сторон. mutate принимает { otherTaskId, direction }:
 * у 'blockedBy' удаляется ребро (otherTaskId → taskId), у 'blocks' — (taskId → otherTaskId).
 */
export function useRemoveDependency(projectId, taskId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ otherTaskId, direction }) =>
      direction === 'blocks'
        ? dependenciesApi.removeDependency(otherTaskId, taskId)
        : dependenciesApi.removeDependency(taskId, otherTaskId),
    onSuccess: (_data, { otherTaskId }) => {
      invalidateAfterLinkChange(queryClient, { projectId, taskId, otherTaskId })
    },
  })
}
