import { apiClient } from './client'

export function fetchProjectWiki(projectId) {
  return apiClient.get(`/projects/${projectId}/wiki`).then((res) => res.data)
}

export function updateProjectWiki(projectId, content) {
  return apiClient.put(`/projects/${projectId}/wiki`, { content }).then((res) => res.data)
}
