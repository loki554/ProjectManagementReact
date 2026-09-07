import { apiClient } from './client'

// Спринты проекта (4.9). Задач в ответе нет: состав спринта — это обычный список задач с
// фильтром sprintId (см. tasksApi.fetchTasks), а не второй способ отдавать задачу.
export function fetchSprints(projectId) {
  return apiClient.get(`/projects/${projectId}/sprints`).then((res) => res.data)
}

export function createSprint(projectId, payload) {
  return apiClient.post(`/projects/${projectId}/sprints`, payload).then((res) => res.data)
}

// PUT, а не PATCH: карточка спринта правится целиком, и снятая цель обязана доехать
// снятой (см. SprintRequest на бэкенде).
export function updateSprint(sprintId, payload) {
  return apiClient.put(`/sprints/${sprintId}`, payload).then((res) => res.data)
}

export function startSprint(sprintId) {
  return apiClient.post(`/sprints/${sprintId}/start`).then((res) => res.data)
}

// moveUnfinishedToSprintId = null означает «недоделанное — в бэклог»; это законный ответ,
// а не отсутствие ответа, поэтому поле отправляется всегда.
export function completeSprint(sprintId, moveUnfinishedToSprintId) {
  return apiClient
    .post(`/sprints/${sprintId}/complete`, { moveUnfinishedToSprintId })
    .then((res) => res.data)
}

export function deleteSprint(sprintId) {
  return apiClient.delete(`/sprints/${sprintId}`)
}
