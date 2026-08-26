import { describe, expect, it } from 'vitest'
import { fromDatetimeLocalValue, toDatetimeLocalValue } from './datetimeLocal'

// Тесты написаны так, чтобы не зависеть от часового пояса машины: ожидания строятся не из
// UTC-констант, а из локальной даты (new Date(y, m, d, ...)) либо из строки datetime-local,
// которую браузер и так трактует как локальную. В vite.config.js зона дополнительно
// зафиксирована на UTC — но проверка от этого зависеть не должна.

describe('toDatetimeLocalValue', () => {
  it('переводит instant в локальное время в формате, который понимает <input type="datetime-local">', () => {
    const local = new Date(2026, 5, 15, 14, 30)

    expect(toDatetimeLocalValue(local.toISOString())).toBe('2026-06-15T14:30')
  })

  it('дополняет однозначные месяц, день, часы и минуты нулём', () => {
    const local = new Date(2026, 0, 5, 3, 7)

    expect(toDatetimeLocalValue(local.toISOString())).toBe('2026-01-05T03:07')
  })

  it('отбрасывает секунды — в поле их всё равно нет', () => {
    const local = new Date(2026, 5, 15, 14, 30, 59)

    expect(toDatetimeLocalValue(local.toISOString())).toBe('2026-06-15T14:30')
  })

  // Пустое поле — это «срок не задан», нормальное состояние, а не ошибка. Через хелпер
  // проходит и null с бэкенда (dueDate необязателен), и '' из самого поля.
  it.each([[null], [undefined], ['']])('%s превращает в пустую строку, а не в Invalid Date', (empty) => {
    expect(toDatetimeLocalValue(empty)).toBe('')
  })
})

describe('fromDatetimeLocalValue', () => {
  it('переводит значение поля в ISO-instant, трактуя его как локальное время', () => {
    const iso = fromDatetimeLocalValue('2026-06-15T14:30')

    expect(iso).toBe(new Date(2026, 5, 15, 14, 30).toISOString())
    expect(iso).toMatch(/Z$/)
  })

  it('пустое поле — это null (срок снят), а не строка и не Invalid Date', () => {
    expect(fromDatetimeLocalValue('')).toBeNull()
    expect(fromDatetimeLocalValue(null)).toBeNull()
    expect(fromDatetimeLocalValue(undefined)).toBeNull()
  })
})

describe('туда и обратно', () => {
  // Главное свойство пары: открыть задачу, ничего не трогая нажать «Сохранить» — и срок
  // не должен поехать. Именно здесь ловится перепутанная зона (getUTCHours вместо getHours).
  it.each([
    ['2026-06-15T14:30'],
    ['2026-01-01T00:00'],
    ['2026-12-31T23:59'],
    ['2026-03-29T03:30'],
  ])('%s переживает круг без изменений', (value) => {
    expect(toDatetimeLocalValue(fromDatetimeLocalValue(value))).toBe(value)
  })
})
