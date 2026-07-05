import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import * as wikiApi from './wikiApi'

const wikiKey = (projectId) => ['projects', projectId, 'wiki']

export function useProjectWiki(projectId) {
  return useQuery({
    queryKey: wikiKey(projectId),
    queryFn: () => wikiApi.fetchProjectWiki(projectId),
    enabled: Boolean(projectId),
  })
}

export function useUpdateWiki(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (content) => wikiApi.updateProjectWiki(projectId, content),
    // PUT возвращает свежий WikiResponse целиком — кладём его в кэш напрямую,
    // без лишнего рефетча.
    onSuccess: (data) => {
      queryClient.setQueryData(wikiKey(projectId), data)
    },
  })
}
