import { describe, expect, it } from 'vitest'
import { parseEventStreamFrame } from './eventStream'

/**
 * Разбор кадров SSE (4.15). Проверять здесь стоит именно его: чтение потока — это цикл над
 * байтами, а вся вероятность ошибки собрана в границах полей. Ошибка при этом получается
 * тихая: кадр, разобранный неправильно, не бросает исключения — он просто не приводит ни к
 * какому обновлению, и выглядит это как «иногда не обновляется».
 */
describe('parseEventStreamFrame', () => {
  it('берёт имя события и данные', () => {
    expect(parseEventStreamFrame('event: change\ndata: {"scope":"project"}')).toEqual({
      name: 'change',
      data: '{"scope":"project"}',
    })
  })

  // Спецификация съедает ровно один пробел после двоеточия, а не все: "data:x" и "data: x"
  // это одно и то же, а "data:  x" — это значение с ведущим пробелом.
  it('съедает ровно один пробел после двоеточия', () => {
    expect(parseEventStreamFrame('data:x').data).toBe('x')
    expect(parseEventStreamFrame('data: x').data).toBe('x')
    expect(parseEventStreamFrame('data:  x').data).toBe(' x')
  })

  it('без event это message — как в браузерном EventSource', () => {
    expect(parseEventStreamFrame('data: hi').name).toBe('message')
  })

  it('многострочная data склеивается переносами', () => {
    expect(parseEventStreamFrame('event: change\ndata: {\ndata: }').data).toBe('{\n}')
  })

  // Heartbeat сервера (RealtimeHeartbeatJob) ходит комментарием. Реагировать на него нечем,
  // и главное — не принять его за событие без данных.
  it('комментарий-heartbeat событием не является', () => {
    expect(parseEventStreamFrame(':ping')).toBeNull()
  })

  it('кадр без data событием не является', () => {
    expect(parseEventStreamFrame('event: change')).toBeNull()
    expect(parseEventStreamFrame('')).toBeNull()
  })

  // id и retry в потоке есть по спецификации, но нам не нужны: историю сервер не хранит
  // (сигнал «сходи посмотри» компенсируется первым же следующим), а паузами
  // переподключения управляет клиент. Главное — чтобы они не ломали разбор.
  it('id и retry игнорируются, но кадр не портят', () => {
    expect(parseEventStreamFrame('id: 42\nretry: 5000\nevent: change\ndata: ok')).toEqual({
      name: 'change',
      data: 'ok',
    })
  })
})
