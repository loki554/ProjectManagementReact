import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import * as taskTemplatesApi from './taskTemplatesApi'

const templatesKey = (projectId) => ['projects', projectId, 'task-templates']
const templateKey = (templateId) => ['task-templates', templateId]

export function useTaskTemplates(projectId) {
  return useQuery({
    queryKey: templatesKey(projectId),
    queryFn: () => taskTemplatesApi.fetchTaskTemplates(projectId),
    enabled: Boolean(projectId),
  })
}

// Один шаблон целиком — с пунктами чек-листа. Запрашивается только тогда, когда шаблон
// выбрали в форме заведения задачи или открыли на правку: тянуть пункты всех шаблонов
// проекта ради списка, в котором видно только имена, незачем.
export function useTaskTemplate(templateId) {
  return useQuery({
    queryKey: templateKey(templateId),
    queryFn: () => taskTemplatesApi.fetchTaskTemplate(templateId),
    enabled: Boolean(templateId),
  })
}

export function useCreateTaskTemplate(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload) => taskTemplatesApi.createTaskTemplate(projectId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: templatesKey(projectId) })
    },
  })
}

export function useUpdateTaskTemplate(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ templateId, payload }) => taskTemplatesApi.updateTaskTemplate(templateId, payload),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: templatesKey(projectId) })
      // Полный шаблон лежит на своём ключе — без этого форма заведения задачи подставила
      // бы старый чек-лист до перезагрузки страницы.
      queryClient.setQueryData(templateKey(data.id), data)
    },
  })
}

export function useDeleteTaskTemplate(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (templateId) => taskTemplatesApi.deleteTaskTemplate(templateId),
    onSuccess: (_data, templateId) => {
      queryClient.invalidateQueries({ queryKey: templatesKey(projectId) })
      queryClient.removeQueries({ queryKey: templateKey(templateId) })
    },
  })
}
