import { apiClient } from './client'

// Шаблоны задач проекта (4.13). Список приходит без пунктов чек-листа, только с их числом —
// за самими пунктами ходят в fetchTaskTemplate, когда шаблон выбрали.
export function fetchTaskTemplates(projectId) {
  return apiClient.get(`/projects/${projectId}/task-templates`).then((res) => res.data)
}

export function fetchTaskTemplate(templateId) {
  return apiClient.get(`/task-templates/${templateId}`).then((res) => res.data)
}

export function createTaskTemplate(projectId, payload) {
  return apiClient.post(`/projects/${projectId}/task-templates`, payload).then((res) => res.data)
}

// PUT, а не PATCH: шаблон правится формой целиком, вместе с чек-листом (см.
// TaskTemplateRequest на бэкенде).
export function updateTaskTemplate(templateId, payload) {
  return apiClient.put(`/task-templates/${templateId}`, payload).then((res) => res.data)
}

export function deleteTaskTemplate(templateId) {
  return apiClient.delete(`/task-templates/${templateId}`)
}
