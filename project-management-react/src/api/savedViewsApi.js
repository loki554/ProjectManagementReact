import { apiClient } from './client'

// Сохранённые представления списка задач (4.7). Персональные: сервер отдаёт только свои,
// чужих не видно никому, включая владельца проекта.
export function fetchSavedViews(projectId) {
  return apiClient.get(`/projects/${projectId}/views`).then((res) => res.data)
}

export function createSavedView(projectId, payload) {
  return apiClient.post(`/projects/${projectId}/views`, payload).then((res) => res.data)
}

// PUT, а не PATCH: «обновить представление» означает «запомни то, что сейчас на экране»,
// то есть снятый фильтр должен исчезнуть. С семантикой PATCH снять его было бы нечем.
export function updateSavedView(viewId, payload) {
  return apiClient.put(`/views/${viewId}`, payload).then((res) => res.data)
}

export function deleteSavedView(viewId) {
  return apiClient.delete(`/views/${viewId}`)
}
