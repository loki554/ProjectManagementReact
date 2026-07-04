import { apiClient } from './client'

export function fetchTags(projectId) {
  return apiClient.get(`/projects/${projectId}/tags`).then((res) => res.data)
}

export function createTag(projectId, payload) {
  return apiClient.post(`/projects/${projectId}/tags`, payload).then((res) => res.data)
}

export function updateTag(tagId, payload) {
  return apiClient.patch(`/tags/${tagId}`, payload).then((res) => res.data)
}

export function deleteTag(tagId) {
  return apiClient.delete(`/tags/${tagId}`)
}
