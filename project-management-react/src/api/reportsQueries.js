import { useQuery } from '@tanstack/react-query'
import * as reportsApi from './reportsApi'

// Фильтры входят в ключ целиком: смена периода — это другой отчёт, а не другое состояние
// того же (тот же приём, что в tasksQueries с параметрами списка).
const timeReportKey = (projectId, filters) => ['projects', projectId, 'reports', 'time', filters]
const dashboardKey = (projectId, sprintId) => ['projects', projectId, 'dashboard', sprintId ?? null]

export function useTimeReport(projectId, filters = {}) {
  return useQuery({
    queryKey: timeReportKey(projectId, filters),
    queryFn: () => reportsApi.fetchTimeReport(projectId, filters),
    enabled: Boolean(projectId),
  })
}

export function useDashboard(projectId, sprintId) {
  return useQuery({
    queryKey: dashboardKey(projectId, sprintId),
    queryFn: () => reportsApi.fetchDashboard(projectId, sprintId),
    enabled: Boolean(projectId),
  })
}
