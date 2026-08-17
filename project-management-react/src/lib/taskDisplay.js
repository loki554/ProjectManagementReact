// Общие форматтеры задачи для списка задач и канбана — чтобы обе страницы
// одинаково подписывали исполнителя, срок и трудозатраты.

// У выполненной/отклонённой задачи просроченный срок уже не важен — подсвечивать
// его красным было бы ложной тревогой.
const FINAL_STATUSES = ['DONE', 'REJECTED']

export function assigneeLabelOf(task) {
  return task.assignee ? `${task.assignee.lastName} ${task.assignee.firstName}` : ''
}

export function isTaskOverdue(task) {
  if (!task.dueDate || FINAL_STATUSES.includes(task.status)) {
    return false
  }
  return new Date(task.dueDate) < new Date()
}

export function formatDueDate(dueDate, locale) {
  return new Date(dueDate).toLocaleString(locale, { dateStyle: 'short', timeStyle: 'short' })
}

// Часы приходят с бэка как BigDecimal-строка ("0.00"). Ноль показываем прочерком:
// колонка/карточка с десятком нулей читается хуже, чем пустая.
export function formatHours(totalHoursSpent) {
  const hours = Number(totalHoursSpent)
  return hours > 0 ? hours.toFixed(2) : null
}
