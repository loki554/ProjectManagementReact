import { apiClient } from './client'

export function fetchProjects() {
  return apiClient.get('/projects').then((res) => res.data)
}

export function fetchProject(projectId) {
  return apiClient.get(`/projects/${projectId}`).then((res) => res.data)
}

// slug (человекочитаемый, используется в URL) или, для обратной совместимости со старыми
// ссылками, сырой UUID — оба формата принимает один и тот же backend-эндпоинт (см.
// ProjectController.getBySlug).
export function fetchProjectBySlug(slug) {
  return apiClient.get(`/projects/slug/${slug}`).then((res) => res.data)
}

export function createProject(payload) {
  return apiClient.post('/projects', payload).then((res) => res.data)
}

export function updateProject(projectId, payload) {
  return apiClient.patch(`/projects/${projectId}`, payload).then((res) => res.data)
}

export function deleteProject(projectId) {
  return apiClient.delete(`/projects/${projectId}`)
}

export function uploadProjectPreviewImage(projectId, file) {
  const formData = new FormData()
  formData.append('file', file)
  return apiClient.post(`/projects/${projectId}/preview-image`, formData).then((res) => res.data)
}

export function fetchProjectStar(projectId) {
  return apiClient.get(`/projects/${projectId}/star`).then((res) => res.data)
}

// PUT/DELETE идемпотентны и оба возвращают актуальный { starCount, starredByMe }.
export function starProject(projectId) {
  return apiClient.put(`/projects/${projectId}/star`).then((res) => res.data)
}

export function unstarProject(projectId) {
  return apiClient.delete(`/projects/${projectId}/star`).then((res) => res.data)
}

export function fetchProjectMembers(projectId) {
  return apiClient.get(`/projects/${projectId}/members`).then((res) => res.data)
}

export function inviteMember(projectId, payload) {
  return apiClient.post(`/projects/${projectId}/members`, payload).then((res) => res.data)
}

export function updateMemberRole(projectId, userId, role) {
  return apiClient.patch(`/projects/${projectId}/members/${userId}`, { role }).then((res) => res.data)
}

export function removeMember(projectId, userId) {
  return apiClient.delete(`/projects/${projectId}/members/${userId}`)
}
