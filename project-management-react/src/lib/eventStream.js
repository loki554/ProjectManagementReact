/**
 * Чтение SSE-потока через fetch (4.15).
 *
 * Штатный браузерный EventSource умеет это сам и в одну строчку — но не умеет отправлять
 * заголовки, а значит, и Authorization. Обходной путь у него один: положить токен в адрес
 * (`/stream?token=...`), а токен в адресной строке попадает в логи прокси и сервера, в
 * историю браузера и в Referer. Для токена, которым открывается всё API, это слишком
 * дорогая плата за экономию тридцати строк разбора кадров.
 *
 * Заодно fetch отдаёт нам то, чего EventSource не отдаёт: код HTTP-ответа (401 — токен
 * протух, надо освежить и повторить; 429 — слишком много вкладок, надо ждать дольше) и
 * управление паузами переподключения. EventSource переподключается сам, но одинаково —
 * и после разрыва сети, и после отказа сервера.
 */

/**
 * Разбирает один кадр SSE — то, что лежит между пустыми строками.
 *
 * Экспортируется отдельно от чтения потока, потому что это единственная его часть, в
 * которой есть что ломать: чтение — это цикл над байтами, а здесь границы полей, значение
 * без пробела после двоеточия, многострочная data и комментарии, которыми ходит heartbeat.
 *
 * @returns {{name: string, data: string}|null} null у кадра без данных — это комментарий
 *          (heartbeat) или пустой кадр, реагировать на него нечем
 */
export function parseEventStreamFrame(frame) {
  let name = 'message'
  const dataLines = []

  for (const line of frame.split('\n')) {
    // Пустая строка внутри кадра и строка, начинающаяся с двоеточия, — это комментарий.
    // Ими же ходит heartbeat сервера (см. RealtimeHeartbeatJob): их задача — не дать
    // прокси счесть соединение мёртвым, и никакого содержимого в них нет.
    if (!line || line.startsWith(':')) {
      continue
    }
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    // "data: x" и "data:x" — одно и то же: по спецификации съедается ровно один пробел
    // после двоеточия, а не все пробелы. Иначе из data пропадали бы ведущие отступы.
    const value = colon === -1 ? '' : line.slice(colon + 1).replace(/^ /, '')

    if (field === 'event') {
      name = value
    } else if (field === 'data') {
      dataLines.push(value)
    }
    // id и retry игнорируем сознательно: id нужен только для Last-Event-ID, а мы не
    // доигрываем пропущенное (сервер и не хранит историю — см. RealtimeMessage: событие
    // это сигнал перечитать, и пропущенный сигнал компенсируется первым же следующим).
    // retry — это указание браузерному EventSource, а паузами здесь управляем мы.
  }

  return dataLines.length > 0 ? { name, data: dataLines.join('\n') } : null
}

/**
 * Открывает поток и вызывает onEvent на каждый кадр, пока его не оборвут через signal или
 * не закроет сервер. Возвращает промис, который завершается вместе с потоком.
 *
 * @throws Error с полем status, если сервер ответил не 2xx
 */
export async function readEventStream(url, { token, signal, onOpen, onEvent }) {
  const response = await fetch(url, {
    headers: { Accept: 'text/event-stream', Authorization: `Bearer ${token}` },
    signal,
    // Ответ, который не заканчивается, кэшировать нечем и незачем, но некоторые прокси
    // пытаются — явный no-store снимает вопрос.
    cache: 'no-store',
  })

  if (!response.ok || !response.body) {
    const error = new Error(`Event stream request failed with ${response.status}`)
    error.status = response.status
    throw error
  }

  onOpen?.()

  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader()
  let buffer = ''

  for (;;) {
    const { value, done } = await reader.read()
    if (done) {
      return
    }
    // Перенос строк нормализуем на всём буфере, а не на куске: \r и \n одного разделителя
    // вполне могут приехать разными чанками, и тогда кадр в середине потока перестал бы
    // находиться — ошибка, которая проявляется раз в сто событий и выглядит как «иногда
    // не обновляется».
    buffer = (buffer + value).replaceAll('\r\n', '\n')

    let boundary = buffer.indexOf('\n\n')
    while (boundary !== -1) {
      const frame = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      const parsed = parseEventStreamFrame(frame)
      if (parsed) {
        onEvent(parsed)
      }
      boundary = buffer.indexOf('\n\n')
    }
  }
}
