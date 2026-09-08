import { apiClient } from './client'

// Отчёт по времени (4.10) и дашборд проекта (4.11) — оба только читают то, что трекер уже
// собрал, поэтому мутаций здесь нет вовсе.

// Пустые фильтры не отправляются: границы периода по умолчанию выбирает сервер (последние
// 30 дней), и подставлять их же на клиенте значило бы завести второе место, где это
// решение принимается.
function reportParams({ from, to, userId }) {
  const params = {}
  if (from) params.from = from
  if (to) params.to = to
  if (userId) params.userId = userId
  return params
}

export function fetchTimeReport(projectId, filters = {}) {
  return apiClient
    .get(`/projects/${projectId}/reports/time`, { params: reportParams(filters) })
    .then((res) => res.data)
}

/**
 * CSV-выгрузка. Эндпоинт требует авторизации, поэтому обычной ссылкой не обойтись — файл
 * приезжает blob'ом через apiClient (тот же приём, что у скачивания вложений), а имя
 * берётся из Content-Disposition: собрал его сервер, и он же знает, какой период на самом
 * деле применился.
 */
export function downloadTimeReportCsv(projectId, filters = {}) {
  return apiClient
    .get(`/projects/${projectId}/reports/time.csv`, {
      params: reportParams(filters),
      responseType: 'blob',
    })
    .then((res) => ({
      blob: res.data,
      contentDisposition: res.headers['content-disposition'],
    }))
}

// sprintId выбирает спринт для burndown; без него сервер берёт идущий, а если такого нет —
// последний завершённый. Остальные графики дашборда от спринта не зависят.
export function fetchDashboard(projectId, sprintId) {
  return apiClient
    .get(`/projects/${projectId}/dashboard`, { params: sprintId ? { sprintId } : {} })
    .then((res) => res.data)
}
