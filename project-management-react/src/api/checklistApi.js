import { apiClient } from './client'

// Чек-лист задачи (4.13).
export function fetchChecklist(taskId) {
  return apiClient.get(`/tasks/${taskId}/checklist`).then((res) => res.data)
}

export function addChecklistItem(taskId, content) {
  return apiClient.post(`/tasks/${taskId}/checklist`, { content }).then((res) => res.data)
}

// PATCH с частичным телом — единственный такой в приложении: галочку ставят кликом по
// строке, текст правят отдельно, и клик по галочке не должен присылать обратно текст,
// затирая чужую правку (см. UpdateChecklistItemRequest на бэкенде).
export function updateChecklistItem(itemId, patch) {
  return apiClient.patch(`/checklist-items/${itemId}`, patch).then((res) => res.data)
}

export function deleteChecklistItem(itemId) {
  return apiClient.delete(`/checklist-items/${itemId}`)
}
