// Порядок отражает убывающий уровень привилегий (OWNER — максимум) — зеркало
// ProjectRole.isAtLeast() на бэкенде (project/ProjectRole.java).
export const PROJECT_ROLES = ['OWNER', 'ADMIN', 'MEMBER', 'VIEWER']

export function roleIsAtLeast(role, required) {
  return PROJECT_ROLES.indexOf(role) <= PROJECT_ROLES.indexOf(required)
}
