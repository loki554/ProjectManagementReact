import { apiClient } from './client'

// PageResponse<NotificationResponse>: { items, page, pageSize, totalItems, totalPages }
export function fetchNotifications(page = 0) {
  return apiClient.get('/notifications', { params: { page } }).then((res) => res.data)
}

export function fetchUnreadNotificationCount() {
  return apiClient.get('/notifications/unread-count').then((res) => res.data.count)
}

export function markNotificationRead(id) {
  return apiClient.post(`/notifications/${id}/read`).then((res) => res.data)
}

export function markAllNotificationsRead() {
  return apiClient.post('/notifications/read-all').then((res) => res.data)
}
