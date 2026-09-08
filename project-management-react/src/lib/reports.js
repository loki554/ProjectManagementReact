// Арифметика и разбор строк для отчёта по времени (4.10) и дашборда (4.11). Вынесено
// отдельным модулем по той же причине, что sprints.js: здесь всё, что легко сломать
// незаметно, — деление на ноль у пустого проекта, полоса шириной 0.3 пикселя, имя файла из
// заголовка, которого нет, — и проверять это тестом надёжнее, чем глазами на живых данных.

/**
 * Имя файла из Content-Disposition. Сервер отдаёт форму RFC 5987
 * (`filename*=UTF-8''time-report-...csv`), но заголовка может не быть вовсе: он не входит
 * в число «безопасных» CORS-заголовков, и стоит кому-то убрать его из exposedHeaders
 * (см. SecurityConfig) — здесь окажется undefined. Тогда берётся запасное имя: скачать
 * файл важнее, чем скачать его с правильным именем.
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
 * Часы для показа: «7» вместо «7.00», «1.5» вместо «1.50». С бэкенда они приезжают строкой
 * (BigDecimal из NUMERIC(5,2) сериализуется как есть, чтобы не терять точность по дороге),
 * и складывать их на клиенте не нужно — суммы уже посчитаны, здесь только показ.
 */
export function formatReportHours(hours) {
  const value = Number(hours ?? 0)
  if (!Number.isFinite(value)) {
    return '0'
  }
  return String(Math.round(value * 100) / 100)
}

/**
 * Доля значения от максимума в процентах, 0..100. Максимум, равный нулю, даёт 0, а не
 * NaN: проект без задач рисует пустые полосы, а не сломанную вёрстку.
 */
export function barPercent(value, max) {
  if (!max || max <= 0) {
    return 0
  }
  return Math.max(0, Math.min(100, (Number(value) / max) * 100))
}

/** Первый день месяца и сегодня — период по умолчанию для формы отчёта. */
export function currentMonthRange(today = new Date()) {
  const year = today.getFullYear()
  const month = today.getMonth()
  return { from: toIsoDate(new Date(year, month, 1)), to: toIsoDate(today) }
}

// Локальная дата в «2026-09-08», а не toISOString(): последний перевёл бы полночь
// 8 сентября в отрицательном поясе в 7-е (та же ловушка, что в sprints.js).
export function toIsoDate(date) {
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

/**
 * Точки burndown в координаты SVG-полилинии.
 *
 * <p>Дни, у которых remaining = null (ещё не наступили), обрываются: линия факта
 * заканчивается на сегодняшнем дне, а не падает в ноль до конца окна — иначе спринт,
 * который только начался, выглядел бы уже сделанным.
 *
 * @returns строка для атрибута points; пустая, если рисовать нечего
 */
export function burndownPolyline(points, { width, height, scope }) {
  if (!points?.length) {
    return ''
  }
  const top = Math.max(scope, 1)
  const step = points.length > 1 ? width / (points.length - 1) : 0
  return points
    .map((point, index) => (point.remaining == null ? null : [index * step, height - (point.remaining / top) * height]))
    .filter(Boolean)
    .map(([x, y]) => `${round(x)},${round(y)}`)
    .join(' ')
}

/** Та же полилиния для идеальной линии — она известна на всё окно и не обрывается. */
export function idealPolyline(points, { width, height, scope }) {
  if (!points?.length) {
    return ''
  }
  const top = Math.max(scope, 1)
  const step = points.length > 1 ? width / (points.length - 1) : 0
  return points
    .map((point, index) => `${round(index * step)},${round(height - (Number(point.ideal ?? 0) / top) * height)}`)
    .join(' ')
}

function round(value) {
  return Math.round(value * 100) / 100
}
