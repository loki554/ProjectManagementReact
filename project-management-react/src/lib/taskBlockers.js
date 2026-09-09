import { getErrorCode } from './errorMessage'
import { confirmAction } from '../stores/confirmStore'

// Код 409, которым бэкенд отвечает на попытку закрыть задачу с незакрытыми блокерами (4.8).
// Это предупреждение, а не запрет: тот же запрос с ignoreBlockers: true проходит.
export const OPEN_BLOCKERS_ERROR = 'TASK_HAS_OPEN_BLOCKERS'

export function isOpenBlockersError(error) {
  return getErrorCode(error) === OPEN_BLOCKERS_ERROR
}

// Номера задач, у которых остались незакрытые блокеры — для вопроса «точно закрываем вот
// эти?» перед массовой правкой. Считается по openBlockerCount уже загруженных строк, а не
// отдельным запросом: список на экране и так их несёт. Список может устареть между
// загрузкой страницы и нажатием — на этот случай тот же отказ ещё раз приходит с сервера,
// см. isOpenBlockersError.
export function blockedTaskNumbers(tasks, taskIds) {
  const selected = new Set(taskIds)
  return tasks
    .filter((task) => selected.has(task.id) && task.status !== 'DONE' && task.openBlockerCount > 0)
    .map((task) => task.taskNumber)
    .sort((left, right) => left - right)
}

// «#3, #7» — как задачи называют в интерфейсе и в ссылках.
export function formatTaskNumbers(numbers) {
  return numbers.map((number) => `#${number}`).join(', ')
}

/**
 * Один и тот же вопрос задаётся из трёх мест — доска, форма задачи и массовая правка, — и
 * все три разбирают один и тот же отказ сервера. Текст и кнопки живут здесь, а не тремя копиями в
 * страницах: разошедшиеся формулировки одного и того же вопроса читаются как разные вопросы.
 *
 * Тон не красный: закрыть заблокированную задачу — не разрушение, а решение человека,
 * которое всегда можно откатить обратным переводом статуса.
 */
export function confirmDoneWithBlockers(t) {
  return confirmAction({
    title: t('tasks.dependencies.doneConfirm'),
    body: t('tasks.dependencies.doneConfirmBody'),
    confirmLabel: t('tasks.dependencies.doneAnyway'),
    tone: 'primary',
  })
}
