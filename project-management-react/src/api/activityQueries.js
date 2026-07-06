import { useInfiniteQuery } from '@tanstack/react-query'
import * as activityApi from './activityApi'

// Бесконечная лента: страницы докачиваются кнопкой «Показать ещё» (без автоскролла).
// taskId (опционально) — лента одной задачи; ключ различает 'all' и конкретную задачу,
// чтобы overview-лента и вкладка на странице задачи кэшировались независимо.
// Инвалидацию task-скоупа делает useCreateComment (комментарий виден на том же экране);
// для остальных собственных действий пользователь попадает на ленту новым переходом.
export function useProjectActivity(projectId, taskId) {
  return useInfiniteQuery({
    queryKey: ['projects', projectId, 'activity', taskId ?? 'all'],
    queryFn: ({ pageParam }) => activityApi.fetchProjectActivity(projectId, pageParam, taskId),
    initialPageParam: 0,
    getNextPageParam: (lastPage) =>
      lastPage.page + 1 < lastPage.totalPages ? lastPage.page + 1 : undefined,
    enabled: Boolean(projectId),
  })
}
