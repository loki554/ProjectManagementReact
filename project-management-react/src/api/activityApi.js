import { apiClient } from './client'

// PageResponse<ActivityResponse>: { items, page, pageSize, totalItems, totalPages }
// taskId (опционально) сужает ленту до событий одной задачи — вкладка «Активность»
// на странице просмотра задачи.
export function fetchProjectActivity(projectId, page, taskId) {
  return apiClient
    .get(`/projects/${projectId}/activity`, { params: { page, taskId } })
    .then((res) => res.data)
}
