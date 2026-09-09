import { describe, expect, it } from 'vitest'
import i18n from '../i18n'
import { describeTaskDeletion } from './taskDeletion'

const t = i18n.t.bind(i18n)
const LOCALE = 'en'

const nothing = { subtasks: 0, comments: 0, attachments: 0, timeLogs: 0 }

describe('describeTaskDeletion', () => {
  it('у пустой задачи — общая фраза про корзину, без перечислений', () => {
    expect(describeTaskDeletion(nothing, t, LOCALE)).toBe(
      'It moves to the trash and can be restored from there within 30 days.',
    )
  })

  it('перечисляет только непустое: «0 вложений» — это шум, за которым тонут настоящие числа', () => {
    expect(describeTaskDeletion({ ...nothing, subtasks: 3, comments: 5 }, t, LOCALE)).toBe(
      'It moves to the trash together with 3 subtasks and 5 comments. You can restore it from there within 30 days.',
    )
  })

  it('единственное число — отдельная форма, а не «1 subtasks»', () => {
    expect(describeTaskDeletion({ ...nothing, subtasks: 1, timeLogs: 1 }, t, LOCALE)).toContain('1 subtask and 1 time entry')
  })

  it('порядок частей фиксирован: от подзадач к записям времени', () => {
    const text = describeTaskDeletion({ subtasks: 1, comments: 2, attachments: 3, timeLogs: 4 }, t, LOCALE)

    expect(text).toContain('1 subtask, 2 comments, 3 attachments, and 4 time entries')
  })

  it('не удалось получить счётчики — вопрос всё равно задаётся, просто без подробностей', () => {
    expect(describeTaskDeletion(null, t, LOCALE)).toBe(
      'It moves to the trash and can be restored from there within 30 days.',
    )
  })
})
