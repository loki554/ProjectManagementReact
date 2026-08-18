import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import * as categoriesApi from './categoriesApi'

const categoriesKey = (projectId) => ['projects', projectId, 'categories']
const tasksKey = (projectId) => ['projects', projectId, 'tasks']

export function useCategories(projectId) {
  return useQuery({
    queryKey: categoriesKey(projectId),
    queryFn: () => categoriesApi.fetchCategories(projectId),
    enabled: Boolean(projectId),
  })
}

// projectId фиксируется на уровне хука — как в tagsQueries.js, расчёт на страницу
// управления категориями одного конкретного проекта.
export function useCreateCategory(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload) => categoriesApi.createCategory(projectId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: categoriesKey(projectId) })
    },
  })
}

// Переименование и удаление меняют то, что показано на самих задачах (бейдж категории
// в списке/канбане/карточке), поэтому в отличие от создания инвалидируем ещё и задачи.
export function useUpdateCategory(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ categoryId, payload }) => categoriesApi.updateCategory(categoryId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: categoriesKey(projectId) })
      queryClient.invalidateQueries({ queryKey: tasksKey(projectId) })
    },
  })
}

export function useDeleteCategory(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (categoryId) => categoriesApi.deleteCategory(categoryId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: categoriesKey(projectId) })
      queryClient.invalidateQueries({ queryKey: tasksKey(projectId) })
    },
  })
}
