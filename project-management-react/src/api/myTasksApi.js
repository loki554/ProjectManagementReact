import { apiClient } from './client'

export function fetchMyActiveTasks(page = 0) {
  return apiClient.get('/tasks/mine', { params: { page } }).then((res) => res.data)
}
