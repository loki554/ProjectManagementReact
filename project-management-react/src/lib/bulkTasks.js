import { fromDatetimeLocalValue } from './datetimeLocal'

// Сборка тела PATCH /projects/{id}/tasks/bulk из состояния панели массовых операций (4.6).
// Вынесено из компонента отдельно, потому что здесь живёт вся семантика запроса, а её
// легко сломать незаметно: разница между «не трогать поле» и «очистить поле» на экране
// выглядит как соседние пункты одного выпадающего списка, а на проводе это разные ключи.

// Пустая строка — «не менять»: это же значение <select> получает по умолчанию, поэтому
// отдельного сентинела для него не нужно.
export const BULK_KEEP = ''
// А вот «снять исполнителя»/«убрать тэг» нужны свои: пустая строка уже занята, а null
// в теле запроса от отсутствующего поля на сервере неотличим (см. BulkUpdateTasksRequest).
export const BULK_UNASSIGN = '__unassign__'
export const BULK_NO_TAG = '__no_tag__'
// «Вернуть в бэклог» — вынуть выделенные задачи из спринта (4.9). Именно через массовую
// правку страница спринтов и наполняет спринт: третьего способа менять задачу заводить
// не пришлось.
export const BULK_NO_SPRINT = '__no_sprint__'

export const EMPTY_BULK_FORM = {
  status: BULK_KEEP,
  assignee: BULK_KEEP,
  tag: BULK_KEEP,
  sprint: BULK_KEEP,
  dueDate: '',
  clearDueDate: false,
}

/**
 * Тело запроса содержит только те поля, которые человек действительно выбрал: всё
 * остальное сервер не трогает. Флаг очистки сильнее значения — как и на бэкенде.
 */
export function buildBulkPayload(form) {
  const payload = {}

  if (form.status) {
    payload.status = form.status
  }

  if (form.assignee === BULK_UNASSIGN) {
    payload.clearAssignee = true
  } else if (form.assignee) {
    payload.assigneeId = form.assignee
  }

  if (form.tag === BULK_NO_TAG) {
    payload.clearTag = true
  } else if (form.tag) {
    payload.tagId = form.tag
  }

  if (form.sprint === BULK_NO_SPRINT) {
    payload.clearSprint = true
  } else if (form.sprint) {
    payload.sprintId = form.sprint
  }

  if (form.clearDueDate) {
    payload.clearDueDate = true
  } else if (form.dueDate) {
    payload.dueDate = fromDatetimeLocalValue(form.dueDate)
  }

  return payload
}

// Кнопка «Применить» заблокирована, пока в панели не выбрано ни одного поля: сервер на
// такой запрос отвечает 400 BULK_UPDATE_NO_CHANGES, и показывать эту ошибку вместо того,
// чтобы не дать её совершить, незачем.
export function isEmptyBulkPayload(payload) {
  return Object.keys(payload).length === 0
}
