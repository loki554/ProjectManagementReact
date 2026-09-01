import { keepPreviousData, useQuery } from '@tanstack/react-query'
import * as searchApi from './searchApi'

// projectId входит в ключ отдельным сегментом, а не внутрь params: выдача по проекту и
// глобальная — это разные эндпоинты, и путать их кэш нельзя.
const searchKey = (projectId, params) => ['search', projectId ?? 'all', params]

/**
 * Страница выдачи поиска. projectId === null — глобальный поиск.
 *
 * keepPreviousData — тем же приёмом, что в useTasks: без него список мигает пустотой на
 * каждый символ, а поиск здесь работает по мере набора.
 */
export function useSearch(projectId, params, enabled = true) {
  return useQuery({
    queryKey: searchKey(projectId, params),
    queryFn: () =>
      projectId ? searchApi.searchInProject(projectId, params) : searchApi.search(params),
    enabled,
    placeholderData: keepPreviousData,
  })
}
