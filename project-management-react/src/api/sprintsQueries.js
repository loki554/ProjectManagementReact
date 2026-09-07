import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import * as sprintsApi from './sprintsApi'

const sprintsKey = (projectId) => ['projects', projectId, 'sprints']
const tasksKey = (projectId) => ['projects', projectId, 'tasks']

export function useSprints(projectId) {
  return useQuery({
    queryKey: sprintsKey(projectId),
    queryFn: () => sprintsApi.fetchSprints(projectId),
    enabled: Boolean(projectId),
  })
}

// projectId фиксируется на уровне хука — как в categoriesQueries: все мутации спринтов
// вызываются со страницы спринтов одного конкретного проекта.
export function useCreateSprint(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload) => sprintsApi.createSprint(projectId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: sprintsKey(projectId) })
    },
  })
}

/**
 * Все мутации, кроме создания, инвалидируют ещё и задачи. Причина у каждой своя, но итог
 * один: бейдж спринта на карточке задачи и её принадлежность спринту меняются вместе со
 * спринтом — переименование правит бейдж, старт правит его статус, завершение и удаление
 * вовсе перекладывают задачи в другой спринт или в бэклог.
 */
export function useUpdateSprint(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ sprintId, payload }) => sprintsApi.updateSprint(sprintId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: sprintsKey(projectId) })
      queryClient.invalidateQueries({ queryKey: tasksKey(projectId) })
    },
  })
}

export function useStartSprint(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (sprintId) => sprintsApi.startSprint(sprintId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: sprintsKey(projectId) })
      queryClient.invalidateQueries({ queryKey: tasksKey(projectId) })
    },
  })
}

export function useCompleteSprint(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ sprintId, moveUnfinishedToSprintId }) =>
      sprintsApi.completeSprint(sprintId, moveUnfinishedToSprintId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: sprintsKey(projectId) })
      queryClient.invalidateQueries({ queryKey: tasksKey(projectId) })
    },
  })
}

export function useDeleteSprint(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (sprintId) => sprintsApi.deleteSprint(sprintId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: sprintsKey(projectId) })
      queryClient.invalidateQueries({ queryKey: tasksKey(projectId) })
    },
  })
}
