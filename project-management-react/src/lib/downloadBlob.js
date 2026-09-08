// Скачивание файла, который требует авторизации. Всё, что трекер отдаёт файлом — вложение
// задачи, CSV отчёта по времени (4.10), выгрузки проекта (4.12), — уезжает под токеном,
// поэтому обычной ссылкой не обойтись: файл приезжает blob'ом через apiClient, а сохранение
// триггерим временным <a download>.
//
// Разбор Content-Disposition живёт здесь же, а не рядом с отчётом, где появился: имя файла и
// его сохранение — две половины одного дела, и с приходом второго потребителя (выгрузки)
// держать их в разных модулях стало значить, что «как скачать файл» отвечают два места.

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

/**
 * Имя файла из Content-Disposition. Сервер отдаёт форму RFC 5987
 * (`filename*=UTF-8''tasks-my-project-2026-09-08.csv`), но заголовка может не быть вовсе: он
 * не входит в число «безопасных» CORS-заголовков, и стоит кому-то убрать его из
 * exposedHeaders (см. SecurityConfig) — здесь окажется undefined. Тогда берётся запасное
 * имя: скачать файл важнее, чем скачать его с правильным именем.
 */
export function filenameFromContentDisposition(header, fallback) {
  if (!header) {
    return fallback
  }
  const extended = /filename\*=UTF-8''([^;]+)/i.exec(header)
  if (extended) {
    try {
      return decodeURIComponent(extended[1].trim())
    } catch {
      // Битая процентная кодировка — не повод не отдать файл.
      return fallback
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(header)
  return plain ? plain[1].trim() : fallback
}

/**
 * Ответ скачивающего запроса → сохранённый файл. Обе половины вместе, потому что порознь их
 * никто не зовёт: имя всегда приезжает из того же ответа, что и содержимое.
 *
 * @param response `{ blob, contentDisposition }` — то, что отдают api-модули выгрузок
 * @param fallbackName имя на случай, если заголовок до браузера не доехал
 */
export function saveDownloadedFile({ blob, contentDisposition }, fallbackName) {
  downloadBlob(blob, filenameFromContentDisposition(contentDisposition, fallbackName))
}
