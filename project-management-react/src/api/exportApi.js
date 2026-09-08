import { apiClient } from './client'

// Выгрузка данных проекта (4.12). Мутаций здесь нет и быть не может: пункт только отдаёт
// наружу то, что трекер уже собрал.
//
// Все три файла едут blob'ом через apiClient, а не по обычной ссылке: эндпоинты требуют
// авторизации, а токен в <a href> не подставить (тот же приём, что у скачивания вложений и
// CSV отчёта по времени). Имя файла приезжает в Content-Disposition — собрал его сервер, и
// он же знает, из какого проекта и на какую дату снята выгрузка.

function downloadFile(projectId, path) {
  return apiClient
    .get(`/projects/${projectId}/export/${path}`, { responseType: 'blob' })
    .then((res) => ({
      blob: res.data,
      contentDisposition: res.headers['content-disposition'],
    }))
}

export function downloadTasksCsv(projectId) {
  return downloadFile(projectId, 'tasks.csv')
}

// responseType: 'blob' и для JSON — сознательно: файл сохраняют, а не показывают, и
// разбирать его здесь, чтобы тут же собрать обратно в Blob, было бы лишней работой и лишним
// местом, где формат выгрузки можно случайно изменить по дороге.
export function downloadTasksJson(projectId) {
  return downloadFile(projectId, 'tasks.json')
}

export function downloadWikiMarkdown(projectId) {
  return downloadFile(projectId, 'wiki.md')
}
