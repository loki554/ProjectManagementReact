import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { assigneeLabelOf, formatDueDate, formatHours, isTaskOverdue } from './taskDisplay'

const NOW = new Date('2026-08-26T12:00:00.000Z')

function task(overrides = {}) {
  return { status: 'NEW', dueDate: null, assignee: null, ...overrides }
}

describe('assigneeLabelOf', () => {
  it('склеивает фамилию и имя', () => {
    expect(assigneeLabelOf(task({ assignee: { lastName: 'Иванов', firstName: 'Иван' } })))
      .toBe('Иванов Иван')
  })

  // Пустая строка, а не 'null null' и не undefined: подпись идёт прямо в разметку карточки.
  it('без исполнителя — пустая строка', () => {
    expect(assigneeLabelOf(task())).toBe('')
  })
})

describe('isTaskOverdue', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(NOW)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('срок в прошлом у активной задачи — просрочена', () => {
    expect(isTaskOverdue(task({ dueDate: '2026-08-25T12:00:00.000Z' }))).toBe(true)
  })

  it('срок в будущем — не просрочена', () => {
    expect(isTaskOverdue(task({ dueDate: '2026-08-27T12:00:00.000Z' }))).toBe(false)
  })

  it('без срока — не просрочена', () => {
    expect(isTaskOverdue(task())).toBe(false)
  })

  // Задачу закрыли позже дедлайна — так бывает, и красная подсветка тут была бы ложной
  // тревогой: делать уже нечего.
  it.each([['DONE'], ['REJECTED']])('завершённая задача (%s) не считается просроченной', (status) => {
    expect(isTaskOverdue(task({ status, dueDate: '2026-08-25T12:00:00.000Z' }))).toBe(false)
  })

  it('остальные статусы просрочку показывают', () => {
    for (const status of ['NEW', 'IN_PROGRESS', 'PAUSED', 'FEEDBACK']) {
      expect(isTaskOverdue(task({ status, dueDate: '2026-08-25T12:00:00.000Z' }))).toBe(true)
    }
  })
})

describe('formatDueDate', () => {
  it('показывает и дату, и время в локальном представлении', () => {
    const local = new Date(2026, 5, 15, 14, 30)

    const formatted = formatDueDate(local.toISOString(), 'en-GB')

    // Проверяем состав, а не точную пунктуацию: разделители в выводе Intl зависят от версии
    // ICU в рантайме, и ассерт на строку целиком ломался бы при обновлении Node.
    expect(formatted).toContain('15/06/2026')
    expect(formatted).toContain('14:30')
  })

  it('учитывает переданную локаль', () => {
    const local = new Date(2026, 5, 15, 14, 30).toISOString()

    expect(formatDueDate(local, 'en-US')).not.toBe(formatDueDate(local, 'de-DE'))
  })
})

describe('formatHours', () => {
  it('приводит строку BigDecimal с бэкенда к двум знакам', () => {
    expect(formatHours('3.5')).toBe('3.50')
    expect(formatHours('0.25')).toBe('0.25')
    expect(formatHours('12')).toBe('12.00')
  })

  // Ноль показывается прочерком (за null отвечает уже вызывающий код): колонка из десятка
  // "0.00" читается хуже, чем пустая.
  it.each([['0.00'], ['0'], [null], [undefined], ['']])('%s — это null, а не "0.00"', (value) => {
    expect(formatHours(value)).toBeNull()
  })

  it('мусор вместо числа тоже даёт null, а не "NaN"', () => {
    expect(formatHours('not a number')).toBeNull()
  })
})
