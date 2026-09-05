import { apiClient } from './client'

// Настройки email-уведомлений (4.3). Живут под /users/me рядом с профилем и паролем —
// это настройки человека, а не коллекции уведомлений.
export function fetchNotificationSettings() {
  return apiClient.get('/users/me/notification-settings').then((res) => res.data)
}

// PATCH, но полная замена: форма показывает все переключатели сразу и отправляет их все.
// Неполное тело бэкенд отвергнет с 400 — намеренно, чтобы забытое поле не выключало
// человеку уведомления молча.
export function updateNotificationSettings(payload) {
  return apiClient.patch('/users/me/notification-settings', payload).then((res) => res.data)
}

// Публичный эндпоинт: по этой ссылке приходят из письма, обычно не заходя в приложение.
// Токен подписан бэкендом (см. UnsubscribeTokenService) и не даёт ничего, кроме отписки.
export function unsubscribeFromNotificationEmails(token) {
  return apiClient.post('/notifications/unsubscribe', { token }).then((res) => res.data)
}
