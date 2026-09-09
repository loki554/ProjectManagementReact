import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import * as checklistApi from './checklistApi'

const checklistKey = (taskId) => ['tasks', taskId, 'checklist']

export function useChecklist(taskId) {
  return useQuery({
    queryKey: checklistKey(taskId),
    queryFn: () => checklistApi.fetchChecklist(taskId),
    enabled: Boolean(taskId),
  })
}

/**
 * Любая правка чек-листа меняет ещё и счётчик «3/7», а он приезжает в каждом ответе о
 * задаче — и в карточке доски, и в строке списка. Поэтому инвалидируется не только сам
 * чек-лист, но и задача с задачами проекта — тем же приёмом, что у зависимостей (4.8),
 * где ровно так же меняется openBlockerCount.
 */
function invalidateAfterChange(queryClient, { projectId, taskId }) {
  queryClient.invalidateQueries({ queryKey: ['tasks', taskId] })
  queryClient.invalidateQueries({ queryKey: ['projects', projectId, 'tasks'] })
}

export function useAddChecklistItem(projectId, taskId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (content) => checklistApi.addChecklistItem(taskId, content),
    onSuccess: () => invalidateAfterChange(queryClient, { projectId, taskId }),
  })
}

/**
 * Отметить пункт или переписать его текст. Галочка — оптимистично, как звезда проекта и
 * перетаскивание на канбане: это клик, после которого ждать ответа сервера, глядя на
 * неотмеченный пункт, невыносимо. При ошибке возвращаемся к снимку.
 */
export function useUpdateChecklistItem(projectId, taskId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ itemId, patch }) => checklistApi.updateChecklistItem(itemId, patch),
    onMutate: async ({ itemId, patch }) => {
      await queryClient.cancelQueries({ queryKey: checklistKey(taskId) })
      const previous = queryClient.getQueryData(checklistKey(taskId))
      if (previous) {
        queryClient.setQueryData(
          checklistKey(taskId),
          previous.map((item) => (item.id === itemId ? { ...item, ...patch } : item)),
        )
      }
      return { previous }
    },
    onError: (_error, _variables, context) => {
      if (context?.previous) {
        queryClient.setQueryData(checklistKey(taskId), context.previous)
      }
    },
    onSettled: () => invalidateAfterChange(queryClient, { projectId, taskId }),
  })
}

export function useDeleteChecklistItem(projectId, taskId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (itemId) => checklistApi.deleteChecklistItem(itemId),
    onSuccess: () => invalidateAfterChange(queryClient, { projectId, taskId }),
  })
}
