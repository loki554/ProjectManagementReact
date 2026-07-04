// <input type="datetime-local"> работает в "локальном время без зоны" формате
// (YYYY-MM-DDTHH:mm) — эти хелперы конвертируют его в/из ISO-instant, который отдаёт
// и принимает бэкенд (Instant).
export function toDatetimeLocalValue(isoString) {
  if (!isoString) return ''
  const d = new Date(isoString)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function fromDatetimeLocalValue(value) {
  if (!value) return null
  return new Date(value).toISOString()
}
