import { apiClient } from './client'

// Табличный список задач: страница ({items, page, pageSize, totalItems, totalPages}), а не
// массив. Фильтрация и сортировка тоже на сервере — по неполному списку их не сделать.
export function fetchTasks(projectId, params = {}) {
  return apiClient
    .get(`/projects/${projectId}/tasks`, { params })
    .then((res) => res.data)
}

// Доска — отдельный эндпоинт и по-прежнему полный массив: канбану нужны все колонки целиком,
// чтобы считать позицию перетаскиваемой карточки по её соседям.
export function fetchBoardTasks(projectId) {
  return apiClient.get(`/projects/${projectId}/tasks/board`).then((res) => res.data)
}

// taskNumber — порядковый номер задачи внутри проекта (#1, #2, ...), используется в читаемых
// URL (/projects/:projectSlug/tasks/:taskNumber) вместо UUID задачи.
export function fetchTaskByNumber(projectId, taskNumber) {
  return apiClient.get(`/projects/${projectId}/tasks/by-number/${taskNumber}`).then((res) => res.data)
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
