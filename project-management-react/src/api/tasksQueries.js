import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import * as attachmentsApi from './attachmentsApi'
import * as commentsApi from './commentsApi'
import * as tasksApi from './tasksApi'
import * as timeLogsApi from './timeLogsApi'

// tasksKey — общий префикс всех задач проекта: по нему инвалидируются разом и доска,
// и любая страница списка, и запрос задачи по номеру.
const tasksKey = (projectId) => ['projects', projectId, 'tasks']
const boardKey = (projectId) => [...tasksKey(projectId), 'board']
const taskListKey = (projectId, params) => [...tasksKey(projectId), 'list', params]
const trashKey = (projectId) => [...tasksKey(projectId), 'trash']
const taskKey = (taskId) => ['tasks', taskId]
const subtasksKey = (taskId) => ['tasks', taskId, 'subtasks']
// Счётчики спринта (4.9) считаются по задачам на сервере, а не хранятся в самом спринте:
// «сделано 3 из 7» меняется и от закрытия задачи, и от переноса её в другой спринт, и от
// удаления. Значит, любая правка задачи протухает и список спринтов — иначе прогресс на
// странице спринтов остаётся прежним до перезагрузки, причём молча.
const sprintsKey = (projectId) => ['projects', projectId, 'sprints']

function invalidateTasksAndSprints(queryClient, projectId) {
  queryClient.invalidateQueries({ queryKey: tasksKey(projectId) })
  queryClient.invalidateQueries({ queryKey: sprintsKey(projectId) })
}

// Страница табличного списка. params (фильтры + сортировка + page/size) уезжают на сервер
// как есть и входят в ключ кэша — каждая комбинация кэшируется отдельно.
// keepPreviousData — чтобы при перелистывании и смене фильтра таблица не мигала пустотой,
// как в useMyActiveTasks.
export function useTasks(projectId, params = {}) {
  return useQuery({
    queryKey: taskListKey(projectId, params),
    queryFn: () => tasksApi.fetchTasks(projectId, params),
    enabled: Boolean(projectId),
    placeholderData: keepPreviousData,
  })
}

// Канбан-доска: все top-level задачи проекта одним массивом (см. fetchBoardTasks).
export function useBoardTasks(projectId) {
  return useQuery({
    queryKey: boardKey(projectId),
    queryFn: () => tasksApi.fetchBoardTasks(projectId),
    enabled: Boolean(projectId),
  })
}

// Разрешает (projectId, taskNumber) из читаемого URL в полную задачу (с её реальным UUID) —
// projectId здесь уже настоящий UUID проекта (разрешён из slug выше по цепочке страницей).
export function useTaskByNumber(projectId, taskNumber) {
  return useQuery({
    queryKey: ['projects', projectId, 'tasks', 'by-number', taskNumber],
    queryFn: () => tasksApi.fetchTaskByNumber(projectId, taskNumber),
    enabled: Boolean(projectId) && Boolean(taskNumber),
  })
}

// projectId фиксируется на хуке, как в useInviteMember — предполагается использование
// со страницы, скоупированной на один проект (список задач/канбан).
export function useCreateTask(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload) => tasksApi.createTask(projectId, payload),
    onSuccess: () => {
      invalidateTasksAndSprints(queryClient, projectId)
    },
  })
}

// taskId фиксируется на хуке, как в useUpdateProject — предполагается использование
// со страницы редактирования одной конкретной задачи (TaskEditPage).
export function useUpdateTask(taskId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload) => tasksApi.updateTask(taskId, payload),
    onSuccess: (data) => {
      queryClient.setQueryData(taskKey(taskId), data)
      invalidateTasksAndSprints(queryClient, data.projectId)
      if (data.parentTaskId) {
        queryClient.invalidateQueries({ queryKey: subtasksKey(data.parentTaskId) })
      }
    },
  })
}

// projectId фиксируется на хуке (как useDeleteProject фиксирует список, из которого удаляют).
// mutate принимает { taskId, parentTaskId? } — parentTaskId нужен, только если удаляемая
// задача сама является подзадачей, чтобы обновить кэш списка подзадач её родителя.
export function useDeleteTask(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ taskId }) => tasksApi.deleteTask(taskId),
    // Намеренно НЕ используем removeQueries(taskKey(taskId))/removeQueries(subtasksKey(taskId)):
    // TaskEditPage, с которой обычно вызывается удаление, ещё смонтирована в момент onSuccess
    // (навигация происходит асинхронно) и подписана на эти же ключи через useTask/useSubtasks —
    // removeQueries на активный запрос заставляет react-query тут же перезапросить удалённую
    // задачу и словить 404 в консоли прямо перед уходом со страницы. Инвалидации родительских
    // списков достаточно: на удалённый id больше никто не подписывается после навигации прочь.
    onSuccess: (_data, { parentTaskId }) => {
      invalidateTasksAndSprints(queryClient, projectId)
      if (parentTaskId) {
        queryClient.invalidateQueries({ queryKey: subtasksKey(parentTaskId) })
      }
    },
  })
}

// Массовая правка (4.6). Optimistic update здесь сознательно нет, в отличие от канбана:
// изменившиеся поля входят в фильтры и сортировку списка, поэтому после правки страница
// меняет и состав, и порядок, и предсказать её на клиенте нельзя — «оптимистично»
// показанный результат разошёлся бы с ответом сервера на первом же активном фильтре.
export function useBulkUpdateTasks(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload) => tasksApi.bulkUpdateTasks(projectId, payload),
    onSuccess: () => {
      invalidateTasksAndSprints(queryClient, projectId)
    },
  })
}

// Клиентское зеркало backend-алгоритма пересчёта position (TaskService.updateStatus, 5.1.2):
// та же "колонка" — top-level задачи одного статуса — перенумеровывается 0..n-1 после
// вставки перемещённой задачи на targetIndex. Работает над плоским кэшированным списком
// без явной группировки: сортировка внутри каждого статуса определяется порядком добавления
// в возвращаемый массив, а группировка по статусу на странице канбана (фильтром по task.status)
// сохраняет этот относительный порядок независимо от чередования с другими статусами.
function reorderTasksOptimistically(tasks, { taskId, status: newStatus, position: targetIndex }) {
  const moved = tasks.find((task) => task.id === taskId)
  if (!moved) {
    return tasks
  }
  const oldStatus = moved.status
  const others = tasks.filter((task) => task.id !== taskId)

  if (oldStatus === newStatus) {
    const column = others.filter((task) => task.status === newStatus).sort((a, b) => a.position - b.position)
    const rest = others.filter((task) => task.status !== newStatus)
    column.splice(targetIndex, 0, moved)
    return [...rest, ...column.map((task, index) => ({ ...task, position: index }))]
  }

  const oldColumn = others.filter((task) => task.status === oldStatus).sort((a, b) => a.position - b.position)
  const newColumn = others.filter((task) => task.status === newStatus).sort((a, b) => a.position - b.position)
  const untouched = others.filter((task) => task.status !== oldStatus && task.status !== newStatus)

  newColumn.splice(targetIndex, 0, { ...moved, status: newStatus })

  return [
    ...untouched,
    ...oldColumn.map((task, index) => ({ ...task, position: index })),
    ...newColumn.map((task, index) => ({ ...task, position: index })),
  ]
}

// projectId фиксируется на хуке, как в useCreateTask — используется с канбан-страницы,
// скоупированной на один проект. Optimistic update: onMutate сразу переставляет задачу
// в кэше (см. reorderTasksOptimistically), onError откатывает к снимку "до", onSettled
// сверяет с сервером — сервер остаётся источником истины для итогового position (см. §6).
export function useUpdateTaskStatus(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    // ignoreBlockers — согласие закрыть задачу с незакрытыми блокерами (4.8); в первом
    // запросе его нет, он появляется только во втором, после подтверждения на 409.
    mutationFn: ({ taskId, status, position, expectedStatus, ignoreBlockers }) =>
      tasksApi.updateTaskStatus(taskId, { status, position, expectedStatus, ignoreBlockers }),
    // Точечно по ключу доски, а не по префиксу tasksKey: под тем же префиксом лежат
    // страницы табличного списка ({items, ...}) и отдельные задачи по номеру, а
    // reorderTasksOptimistically умеет только плоский массив доски.
    onMutate: async ({ taskId, status, position }) => {
      await queryClient.cancelQueries({ queryKey: boardKey(projectId) })
      const previous = queryClient.getQueryData(boardKey(projectId))
      queryClient.setQueryData(boardKey(projectId), (old) =>
        old ? reorderTasksOptimistically(old, { taskId, status, position }) : old,
      )
      return { previous }
    },
    onError: (_error, _variables, context) => {
      if (context?.previous) {
        queryClient.setQueryData(boardKey(projectId), context.previous)
      }
    },
    onSettled: () => {
      invalidateTasksAndSprints(queryClient, projectId)
    },
  })
}

// Корзина проекта (3.5).
export function useTrash(projectId) {
  return useQuery({
    queryKey: trashKey(projectId),
    queryFn: () => tasksApi.fetchTrash(projectId),
    enabled: Boolean(projectId),
  })
}

// Инвалидируем весь префикс проекта: восстановленная задача возвращается и в корзину
// (её там больше нет), и в список, и на доску.
export function useRestoreTask(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (taskId) => tasksApi.restoreTask(taskId),
    onSuccess: () => {
      invalidateTasksAndSprints(queryClient, projectId)
    },
  })
}

export function useSubtasks(taskId) {
  return useQuery({
    queryKey: subtasksKey(taskId),
    queryFn: () => tasksApi.fetchSubtasks(taskId),
    enabled: Boolean(taskId),
  })
}

// parentTaskId фиксируется на хуке — используется из TaskViewPage конкретной родительской задачи.
export function useCreateSubtask(parentTaskId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload) => tasksApi.createSubtask(parentTaskId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: subtasksKey(parentTaskId) })
    },
  })
}

/**
 * Сколько всего уедет в корзину вместе с задачей — для вопроса перед удалением (5.2).
 * Четыре списка одним запросом не отдаются, поэтому спрашиваем все четыре разом и только в момент
 * нажатия: грузить ими открытие формы ради кнопки, которую могут и не нажать, незачем.
 *
 * Отдельный ключ кэша, а не чтение чужих: список комментариев лежит в кэше под выбранной
 * человеком сортировкой, и угадывать её здесь значило бы связать вопрос об удалении с состоянием
 * селекта на соседней странице. staleTime — чтобы «нажал, передумал, нажал снова» не стоило
 * восьми запросов.
 */
export function fetchTaskDeletionSummary(queryClient, taskId) {
  return queryClient.fetchQuery({
    queryKey: [...taskKey(taskId), 'deletion-summary'],
    staleTime: 30_000,
    queryFn: async () => {
      const [subtasks, comments, attachments, timeLogs] = await Promise.all([
        tasksApi.fetchSubtasks(taskId),
        commentsApi.fetchComments(taskId, 'newest'),
        attachmentsApi.fetchAttachments(taskId),
        timeLogsApi.fetchTimeLogs(taskId),
      ])
      return {
        subtasks: subtasks.length,
        comments: comments.length,
        attachments: attachments.length,
        timeLogs: timeLogs.length,
      }
    },
  })
}
