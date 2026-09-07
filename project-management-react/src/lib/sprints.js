// Прогресс, сроки и статусы спринта (4.9) — то, что страница спринтов считает по ответу
// сервера. Вынесено отдельным модулем по той же причине, что bulkTasks.js и taskFilters.js:
// здесь арифметика, которую легко сломать незаметно (деление на ноль у пустого спринта,
// «остался 0 дней» вместо «последний день»), и её проще проверить тестом, чем глазами
// на живом проекте, где нужный спринт ещё надо завести.

// Зеркало SprintStatus на бэкенде (sprint/SprintStatus.java). Порядок — порядок
// жизненного цикла, он же порядок кнопок фильтра на странице.
export const SPRINT_STATUSES = ['PLANNED', 'ACTIVE', 'COMPLETED']

const SPRINT_STATUS_BADGE_CLASSES = {
  PLANNED: 'bg-gray-100 text-gray-700 dark:bg-gray-700 dark:text-gray-200',
  ACTIVE: 'bg-green-100 text-green-700 dark:bg-green-900/50 dark:text-green-300',
  COMPLETED: 'bg-gray-100 text-gray-500 dark:bg-gray-700 dark:text-gray-400',
}

export function sprintStatusBadgeClass(status) {
  return SPRINT_STATUS_BADGE_CLASSES[status] ?? SPRINT_STATUS_BADGE_CLASSES.PLANNED
}

/**
 * Доля закрытых задач, 0..100. У пустого спринта — 0, а не «сделано всё»: полоса на сто
 * процентов у спринта без единой задачи читается как выполненный план, хотя плана нет.
 */
export function sprintProgressPercent(sprint) {
  if (!sprint?.taskCount) {
    return 0
  }
  return Math.round((sprint.closedTaskCount / sprint.taskCount) * 100)
}

/** Сколько задач спринта ещё не закрыто — то, что переедет при его завершении. */
export function sprintOpenTaskCount(sprint) {
  return Math.max((sprint?.taskCount ?? 0) - (sprint?.closedTaskCount ?? 0), 0)
}

/**
 * Сколько дней осталось до конца спринта включительно: 1 — «сегодня последний день»,
 * 0 и меньше — окно уже закрылось. Считается по календарным датам, а не по разнице
 * миллисекунд: спринт заканчивается днём, а не моментом времени, и «осталось 0.4 дня»
 * здесь не значит ничего.
 *
 * @param today передаётся явно — иначе функцию нельзя проверить, не подменяя часы
 */
export function daysLeft(endDate, today = new Date()) {
  const end = Date.UTC(...isoDateParts(endDate))
  const now = Date.UTC(today.getFullYear(), today.getMonth(), today.getDate())
  return Math.round((end - now) / 86400000) + 1
}

/**
 * Спринт просрочен: окно закрылось, а он всё ещё идёт. Про запланированные и завершённые
 * это не утверждение — у первых окно ещё не наступало, у вторых уже неважно.
 */
export function isSprintOverdue(sprint, today = new Date()) {
  return sprint?.status === 'ACTIVE' && daysLeft(sprint.endDate, today) <= 0
}

// Даты приходят с бэкенда как "2026-09-07" (LocalDate). new Date("2026-09-07") разобрал бы
// её как UTC-полночь и в отрицательных часовых поясах показал бы предыдущий день —
// поэтому строка разбирается по частям и собирается локальной датой.
export function formatSprintDate(isoDate, locale) {
  const [year, month, day] = isoDateParts(isoDate)
  return new Date(year, month, day).toLocaleDateString(locale, { day: 'numeric', month: 'short' })
}

export function formatSprintRange(sprint, locale) {
  const from = formatSprintDate(sprint.startDate, locale)
  const to = formatSprintDate(sprint.endDate, locale)
  // Майлстоун — спринт со схлопнутым окном; «18 сент. — 18 сент.» на экране выглядит
  // как ошибка, поэтому одна дата и показывается одной датой.
  return from === to ? from : `${from} — ${to}`
}

function isoDateParts(isoDate) {
  const [year, month, day] = String(isoDate).split('-').map(Number)
  return [year, month - 1, day]
}
