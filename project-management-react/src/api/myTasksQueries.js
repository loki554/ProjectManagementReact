import { keepPreviousData, useQuery } from '@tanstack/react-query'
import * as myTasksApi from './myTasksApi'

// keepPreviousData — не мигать пустым/loading-состоянием при переключении страницы,
// пока грузится следующая (тот же приём подошёл бы любому будущему постранично выдаваемому списку).
export function useMyActiveTasks(page) {
  return useQuery({
    queryKey: ['tasks', 'mine', 'active', page],
    queryFn: () => myTasksApi.fetchMyActiveTasks(page),
    placeholderData: keepPreviousData,
  })
}
