import { describe, expect, it } from 'vitest'
import { blockedTaskNumbers, formatTaskNumbers, isOpenBlockersError } from './taskBlockers'

function task(overrides) {
  return { id: 'task-1', taskNumber: 1, status: 'NEW', openBlockerCount: 0, ...overrides }
}

describe('isOpenBlockersError', () => {
  it('узнаёт код 409 про незакрытые блокеры', () => {
    expect(isOpenBlockersError({ response: { data: { error: 'TASK_HAS_OPEN_BLOCKERS' } } })).toBe(true)
  })

  // Соседний 409 (конфликт версий) обязан остаться обычной ошибкой: спрашивать
  // «всё равно закрыть?» там нечего — там надо перечитать страницу.
  it('не путает с другими конфликтами и с сетевым сбоем', () => {
    expect(isOpenBlockersError({ response: { data: { error: 'CONCURRENT_MODIFICATION' } } })).toBe(false)
    expect(isOpenBlockersError(new Error('Network Error'))).toBe(false)
    expect(isOpenBlockersError(undefined)).toBe(false)
  })
})

describe('blockedTaskNumbers', () => {
  const tasks = [
    task({ id: 'a', taskNumber: 7, openBlockerCount: 2 }),
    task({ id: 'b', taskNumber: 3, openBlockerCount: 1 }),
    task({ id: 'c', taskNumber: 5, openBlockerCount: 0 }),
    task({ id: 'd', taskNumber: 9, openBlockerCount: 3, status: 'DONE' }),
  ]

  it('берёт только выделенные заблокированные задачи, по возрастанию номера', () => {
    expect(blockedTaskNumbers(tasks, ['a', 'b', 'c'])).toEqual([3, 7])
  })

  // Задача уже в DONE — вопрос про неё бессмысленен: закрывать её повторно
  // массовая правка не будет, статус у неё и так целевой.
  it('уже закрытая задача в вопрос не попадает', () => {
    expect(blockedTaskNumbers(tasks, ['d'])).toEqual([])
  })

  it('невыделенная заблокированная задача в вопрос не попадает', () => {
    expect(blockedTaskNumbers(tasks, ['c'])).toEqual([])
  })

  it('без блокеров вопроса не будет вовсе', () => {
    expect(blockedTaskNumbers([task({ id: 'a' })], ['a'])).toEqual([])
  })
})

describe('formatTaskNumbers', () => {
  it('номера пишутся так же, как их видно в интерфейсе', () => {
    expect(formatTaskNumbers([3, 7])).toBe('#3, #7')
    expect(formatTaskNumbers([])).toBe('')
  })
})
