import { apiClient } from './client'

// Полнотекстовый поиск (4.1). Ответ — страница ({items, page, pageSize, totalItems,
// totalPages}) того же вида, что у списка задач; items — карточки трёх типов (TASK,
// COMMENT, WIKI), различаются полем type.
export function search(params = {}) {
  return apiClient.get('/search', { params }).then((res) => res.data)
}

// Тот же ответ, но выдача ограничена одним проектом. Отдельный эндпоинт, а не параметр
// глобального: проверка доступа к проекту у него обычная (403 не участнику), тогда как
// глобальный молча показывает только те проекты, где пользователь состоит.
export function searchInProject(projectId, params = {}) {
  return apiClient.get(`/projects/${projectId}/search`, { params }).then((res) => res.data)
}
