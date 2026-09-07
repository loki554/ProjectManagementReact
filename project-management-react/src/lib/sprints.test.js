import { describe, expect, it } from 'vitest'
import {
  daysLeft,
  formatSprintRange,
  isSprintOverdue,
  sprintOpenTaskCount,
  sprintProgressPercent,
} from './sprints'

function sprint(overrides) {
  return {
    id: 'sprint-1',
    name: 'Спринт 1',
    status: 'ACTIVE',
    startDate: '2026-09-07',
    endDate: '2026-09-18',
    taskCount: 0,
    closedTaskCount: 0,
    ...overrides,
  }
}

describe('sprintProgressPercent', () => {
  it('считает долю закрытых задач', () => {
    expect(sprintProgressPercent(sprint({ taskCount: 4, closedTaskCount: 1 }))).toBe(25)
    expect(sprintProgressPercent(sprint({ taskCount: 3, closedTaskCount: 3 }))).toBe(100)
  })

  // Пустой спринт — это план, которого нет, а не выполненный план: 0/0 не должно
  // превращаться ни в NaN на экране, ни в стопроцентную полосу.
  it('у пустого спринта прогресс нулевой, а не NaN', () => {
    expect(sprintProgressPercent(sprint())).toBe(0)
    expect(sprintProgressPercent(undefined)).toBe(0)
  })
})

describe('sprintOpenTaskCount', () => {
  it('показывает, сколько задач переедет при закрытии спринта', () => {
    expect(sprintOpenTaskCount(sprint({ taskCount: 7, closedTaskCount: 5 }))).toBe(2)
    expect(sprintOpenTaskCount(sprint({ taskCount: 7, closedTaskCount: 7 }))).toBe(0)
  })
})

describe('daysLeft', () => {
  // Последний день спринта — это ещё день работы, а не ноль оставшихся дней; отсюда
  // включительный отсчёт.
  it('в последний день окна остаётся один день', () => {
    expect(daysLeft('2026-09-18', new Date(2026, 8, 18))).toBe(1)
  })

  it('до конца окна считает календарные дни', () => {
    expect(daysLeft('2026-09-18', new Date(2026, 8, 7))).toBe(12)
  })

  it('после окончания уходит в ноль и минус', () => {
    expect(daysLeft('2026-09-18', new Date(2026, 8, 19))).toBe(0)
    expect(daysLeft('2026-09-18', new Date(2026, 8, 21))).toBe(-2)
  })

  // Время суток не должно влиять: спринт заканчивается днём, а не моментом.
  it('не зависит от времени суток', () => {
    expect(daysLeft('2026-09-18', new Date(2026, 8, 17, 23, 59))).toBe(2)
    expect(daysLeft('2026-09-18', new Date(2026, 8, 17, 0, 1))).toBe(2)
  })
})

describe('isSprintOverdue', () => {
  it('идущий спринт с прошедшим окном просрочен', () => {
    expect(isSprintOverdue(sprint(), new Date(2026, 8, 25))).toBe(true)
  })

  it('идущий спринт внутри окна не просрочен', () => {
    expect(isSprintOverdue(sprint(), new Date(2026, 8, 10))).toBe(false)
  })

  // У запланированного окно ещё не наступало, у завершённого уже неважно — красным
  // подсвечивать нечего ни там, ни там.
  it('про запланированный и завершённый ничего не утверждает', () => {
    expect(isSprintOverdue(sprint({ status: 'PLANNED' }), new Date(2026, 8, 25))).toBe(false)
    expect(isSprintOverdue(sprint({ status: 'COMPLETED' }), new Date(2026, 8, 25))).toBe(false)
  })
})

describe('formatSprintRange', () => {
  it('окно показывается двумя датами', () => {
    expect(formatSprintRange(sprint(), 'ru')).toContain('—')
  })

  // Майлстоун — спринт с одним днём: «18 сент. — 18 сент.» выглядит как ошибка вёрстки.
  it('майлстоун показывается одной датой', () => {
    const range = formatSprintRange(sprint({ startDate: '2026-09-18', endDate: '2026-09-18' }), 'ru')
    expect(range).not.toContain('—')
  })

  // Дата приходит строкой "2026-09-01"; разобранная как UTC-полночь, она в
  // отрицательных поясах показала бы 31 августа.
  it('первое число месяца не уезжает на день назад', () => {
    expect(formatSprintRange(sprint({ startDate: '2026-09-01', endDate: '2026-09-01' }), 'ru'))
      .toContain('1')
  })
})
