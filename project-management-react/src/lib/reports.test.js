import { describe, expect, it } from 'vitest'
import {
  barPercent,
  burndownPolyline,
  currentMonthRange,
  formatReportHours,
  idealPolyline,
  toIsoDate,
} from './reports'

describe('formatReportHours', () => {
  it('убирает хвостовые нули у часов, приезжающих строкой', () => {
    expect(formatReportHours('7.00')).toBe('7')
    expect(formatReportHours('1.50')).toBe('1.5')
    expect(formatReportHours('0.25')).toBe('0.25')
  })

  it('пустое значение — это ноль, а не NaN на экране', () => {
    expect(formatReportHours(null)).toBe('0')
    expect(formatReportHours(undefined)).toBe('0')
    expect(formatReportHours('не число')).toBe('0')
  })
})

describe('barPercent', () => {
  it('считает долю от максимума', () => {
    expect(barPercent(5, 10)).toBe(50)
    expect(barPercent(10, 10)).toBe(100)
  })

  // Проект без задач: полосы должны быть пустыми, а не сломать вёрстку шириной NaN%.
  it('нулевой максимум даёт ноль, а не деление на ноль', () => {
    expect(barPercent(0, 0)).toBe(0)
    expect(barPercent(3, 0)).toBe(0)
  })

  it('не выходит за 0..100', () => {
    expect(barPercent(15, 10)).toBe(100)
    expect(barPercent(-3, 10)).toBe(0)
  })
})

describe('currentMonthRange', () => {
  it('даёт период «с первого числа по сегодня»', () => {
    expect(currentMonthRange(new Date(2026, 8, 8))).toEqual({ from: '2026-09-01', to: '2026-09-08' })
  })

  // Та же ловушка, что в sprints.js: toISOString() в отрицательном поясе сдвинул бы
  // локальную полночь на предыдущий день.
  it('собирает дату по локальным частям, а не через UTC', () => {
    expect(toIsoDate(new Date(2026, 0, 1))).toBe('2026-01-01')
    expect(toIsoDate(new Date(2026, 11, 31))).toBe('2026-12-31')
  })
})

describe('burndownPolyline', () => {
  const box = { width: 100, height: 50, scope: 4 }

  it('раскладывает точки по ширине окна и переворачивает ось Y', () => {
    const points = [
      { date: '2026-09-01', remaining: 4, ideal: '4.0' },
      { date: '2026-09-02', remaining: 2, ideal: '2.0' },
      { date: '2026-09-03', remaining: 0, ideal: '0.0' },
    ]
    // 4 из 4 — у верхнего края (y = 0), 0 — у нижнего (y = height).
    expect(burndownPolyline(points, box)).toBe('0,0 50,25 100,50')
  })

  // Дни, которые ещё не наступили, приезжают с remaining = null — линия факта обязана
  // на них оборваться, иначе спринт, начавшийся вчера, выглядит выполненным.
  it('обрывает линию факта на будущих днях, а идеальную ведёт до конца', () => {
    const points = [
      { date: '2026-09-01', remaining: 4, ideal: '4.0' },
      { date: '2026-09-02', remaining: 3, ideal: '2.0' },
      { date: '2026-09-03', remaining: null, ideal: '0.0' },
    ]
    expect(burndownPolyline(points, box)).toBe('0,0 50,12.5')
    expect(idealPolyline(points, box)).toBe('0,0 50,25 100,50')
  })

  it('пустой спринт не роняет график', () => {
    expect(burndownPolyline([], box)).toBe('')
    expect(burndownPolyline(undefined, box)).toBe('')
    expect(idealPolyline([{ date: '2026-09-01', remaining: 0, ideal: '0.0' }], { ...box, scope: 0 })).toBe('0,50')
  })
})
