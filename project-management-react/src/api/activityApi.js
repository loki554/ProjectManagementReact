import { apiClient } from './client'

// PageResponse<ActivityResponse>: { items, page, pageSize, totalItems, totalPages }
export function fetchProjectActivity(projectId, page) {
  return apiClient
    .get(`/projects/${projectId}/activity`, { params: { page } })
    .then((res) => res.data)
}
