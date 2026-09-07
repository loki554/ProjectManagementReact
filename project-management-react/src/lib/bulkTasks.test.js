import { describe, expect, it } from 'vitest'
import {
  BULK_NO_TAG,
  BULK_UNASSIGN,
  EMPTY_BULK_FORM,
  buildBulkPayload,
  isEmptyBulkPayload,
} from './bulkTasks'

function form(overrides = {}) {
  return { ...EMPTY_BULK_FORM, ...overrides }
}

describe('buildBulkPayload', () => {
  it('ничего не выбрано — пустое тело', () => {
    const payload = buildBulkPayload(form())
    expect(payload).toEqual({})
    expect(isEmptyBulkPayload(payload)).toBe(true)
  })

  // Главное свойство массовой правки: невыбранное поле не должно доехать до сервера
  // вовсе — иначе «сменить статус пятидесяти задачам» заодно снесёт им исполнителей.
  it('выбран только статус — в теле только он', () => {
    expect(buildBulkPayload(form({ status: 'DONE' }))).toEqual({ status: 'DONE' })
  })

  it('исполнитель и тэг едут id-шниками', () => {
    expect(buildBulkPayload(form({ assignee: 'user-1', tag: 'tag-1' }))).toEqual({
      assigneeId: 'user-1',
      tagId: 'tag-1',
    })
  })

  // «Снять» — отдельный флаг, а не null: см. комментарий в bulkTasks.js.
  it('снять исполнителя и тэг — флаги, а не пустые id', () => {
    const payload = buildBulkPayload(form({ assignee: BULK_UNASSIGN, tag: BULK_NO_TAG }))
    expect(payload).toEqual({ clearAssignee: true, clearTag: true })
    expect(payload).not.toHaveProperty('assigneeId')
    expect(payload).not.toHaveProperty('tagId')
  })

  it('срок уезжает ISO-инстантом, а не строкой из datetime-local', () => {
    const payload = buildBulkPayload(form({ dueDate: '2026-09-10T18:30' }))
    expect(payload.dueDate).toBe(new Date('2026-09-10T18:30').toISOString())
  })

  // Флаг очистки сильнее значения — как и на бэкенде. Иначе забытая в поле дата
  // отменяла бы галочку «снять срок», причём молча.
  it('снять срок сильнее введённой даты', () => {
    expect(buildBulkPayload(form({ dueDate: '2026-09-10T18:30', clearDueDate: true }))).toEqual({
      clearDueDate: true,
    })
  })

  it('несколько полей разом — одно тело', () => {
    expect(
      buildBulkPayload(form({ status: 'IN_PROGRESS', assignee: 'user-1', clearDueDate: true })),
    ).toEqual({ status: 'IN_PROGRESS', assigneeId: 'user-1', clearDueDate: true })
  })
})
