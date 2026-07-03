import { apiClient } from './client'

export function fetchTasks(projectId, filters = {}) {
  return apiClient
    .get(`/projects/${projectId}/tasks`, { params: filters })
    .then((res) => res.data)
}

export function fetchTask(taskId) {
  return apiClient.get(`/tasks/${taskId}`).then((res) => res.data)
}

export function createTask(projectId, payload) {
  return apiClient.post(`/projects/${projectId}/tasks`, payload).then((res) => res.data)
}

export function updateTask(taskId, payload) {
  return apiClient.patch(`/tasks/${taskId}`, payload).then((res) => res.data)
}

export function updateTaskStatus(taskId, payload) {
  return apiClient.patch(`/tasks/${taskId}/status`, payload).then((res) => res.data)
}

export function deleteTask(taskId) {
  return apiClient.delete(`/tasks/${taskId}`)
}

export function fetchSubtasks(taskId) {
  return apiClient.get(`/tasks/${taskId}/subtasks`).then((res) => res.data)
}

export function createSubtask(taskId, payload) {
  return apiClient.post(`/tasks/${taskId}/subtasks`, payload).then((res) => res.data)
}
