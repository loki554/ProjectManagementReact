import { apiClient } from './client'

export function fetchTimeLogs(taskId) {
  return apiClient.get(`/tasks/${taskId}/time-logs`).then((res) => res.data)
}

export function createTimeLog(taskId, payload) {
  return apiClient.post(`/tasks/${taskId}/time-logs`, payload).then((res) => res.data)
}

export function deleteTimeLog(timeLogId) {
  return apiClient.delete(`/time-logs/${timeLogId}`)
}
