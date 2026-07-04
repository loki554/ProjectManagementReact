import { apiClient } from './client'

export function fetchAttachments(taskId) {
  return apiClient.get(`/tasks/${taskId}/attachments`).then((res) => res.data)
}

export function uploadAttachment(taskId, file) {
  const formData = new FormData()
  formData.append('file', file)
  // Content-Type не выставляем вручную — axios сам поставит multipart/form-data
  // с правильным boundary при виде FormData (тот же приём, что и uploadAvatar).
  return apiClient.post(`/tasks/${taskId}/attachments`, formData).then((res) => res.data)
}

// Скачивание требует авторизации (см. AttachmentController.download на бэке), поэтому
// обычная ссылка/window.open без токена не сработает — тянем байты через apiClient
// (interceptor подставит Authorization) и возвращаем Blob для сохранения на клиенте.
// originalFilename для имени сохраняемого файла уже известен из списка вложений —
// парсить Content-Disposition на фронте не нужно.
export function downloadAttachment(attachmentId) {
  return apiClient
    .get(`/attachments/${attachmentId}/download`, { responseType: 'blob' })
    .then((res) => res.data)
}

export function deleteAttachment(attachmentId) {
  return apiClient.delete(`/attachments/${attachmentId}`)
}
