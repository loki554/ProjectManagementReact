import { useInfiniteQuery } from '@tanstack/react-query'
import * as activityApi from './activityApi'

// Бесконечная лента: страницы докачиваются кнопкой «Показать ещё» (без автоскролла).
// Инвалидация не нужна: после собственных действий пользователь попадает на overview
// новым переходом, а чужие события в реальном времени — вне скоупа фазы.
export function useProjectActivity(projectId) {
  return useInfiniteQuery({
    queryKey: ['projects', projectId, 'activity'],
    queryFn: ({ pageParam }) => activityApi.fetchProjectActivity(projectId, pageParam),
    initialPageParam: 0,
    getNextPageParam: (lastPage) =>
      lastPage.page + 1 < lastPage.totalPages ? lastPage.page + 1 : undefined,
    enabled: Boolean(projectId),
  })
}
