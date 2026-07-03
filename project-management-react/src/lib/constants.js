// Порядок отражает убывающий уровень привилегий (OWNER — максимум) — зеркало
// ProjectRole.isAtLeast() на бэкенде (project/ProjectRole.java).
export const PROJECT_ROLES = ['OWNER', 'ADMIN', 'MEMBER', 'VIEWER']

export function roleIsAtLeast(role, required) {
  return PROJECT_ROLES.indexOf(role) <= PROJECT_ROLES.indexOf(required)
}

// Фиксированный набор статусов задачи — зеркало TaskStatus на бэкенде
// (task/TaskStatus.java). Порядок = порядок колонок будущего канбана (Phase 5).
export const TASK_STATUSES = ['NEW', 'IN_PROGRESS', 'PAUSED', 'FEEDBACK', 'DONE', 'REJECTED']

const TASK_STATUS_BADGE_CLASSES = {
  NEW: 'bg-gray-100 text-gray-700',
  IN_PROGRESS: 'bg-blue-100 text-blue-700',
  PAUSED: 'bg-amber-100 text-amber-700',
  FEEDBACK: 'bg-purple-100 text-purple-700',
  DONE: 'bg-green-100 text-green-700',
  REJECTED: 'bg-red-100 text-red-700',
}

export function taskStatusBadgeClass(status) {
  return TASK_STATUS_BADGE_CLASSES[status] ?? TASK_STATUS_BADGE_CLASSES.NEW
}
