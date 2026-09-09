import { apiClient } from './client'

// Режим уведомлений текущего пользователя по проекту (4.16): ALL | PARTICIPATING | MUTED.
export function fetchProjectNotificationMode(projectId) {
  return apiClient.get(`/projects/${projectId}/notification-settings`).then((res) => res.data)
}

// PUT идемпотентен и возвращает актуальный { mode } — как и тумблер звезды рядом.
export function setProjectNotificationMode(projectId, mode) {
  return apiClient
    .put(`/projects/${projectId}/notification-settings`, { mode })
    .then((res) => res.data)
}
