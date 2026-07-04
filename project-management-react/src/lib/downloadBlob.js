// Скачивание идёт через apiClient как blob (эндпоинт требует авторизации, см.
// attachmentsApi.downloadAttachment), поэтому обычной ссылкой не обойтись — триггерим
// сохранение файла через временный <a download> и сразу освобождаем object URL.
export function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}
