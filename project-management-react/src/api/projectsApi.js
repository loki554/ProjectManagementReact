import { apiClient } from './client'

export function fetchProjects() {
  return apiClient.get('/projects').then((res) => res.data)
}

export function fetchProject(projectId) {
  return apiClient.get(`/projects/${projectId}`).then((res) => res.data)
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
