import { zodResolver } from '@hookform/resolvers/zod'
import {
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  closestCorners,
  useDroppable,
  useSensor,
  useSensors,
} from '@dnd-kit/core'
import { SortableContext, sortableKeyboardCoordinates, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { CalendarClock, Clock3 } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { useNavigate, useParams } from 'react-router-dom'
import { z } from 'zod'
import { useCreateTask, useTasks, useUpdateTaskStatus } from '../../api/tasksQueries'
import { useProjectBySlug, useProjectMembers } from '../../api/projectsQueries'
import { UserAvatar } from '../../components/ui/UserAvatar'
import { inputClass, primaryButtonClass } from '../../components/ui/FormKit'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import {
  TASK_NUMBER_BADGE_CLASS,
  TASK_STATUSES,
  roleIsAtLeast,
  taskStatusAccentClass,
  taskStatusBadgeClass,
  taskUrgencyBadgeClass,
} from '../../lib/constants'
import { tagBadgeStyle } from '../../lib/tagColor'
import { assigneeLabelOf, formatDueDate, formatHours, isTaskOverdue } from '../../lib/taskDisplay'
import { useAuthStore } from '../../stores/authStore'

function buildCreateTaskSchema(t) {
  return z.object({
    title: z.string().min(1, t('auth.validation.required')).max(255),
  })
}

// Позиция цели drop'а — зеркало backend-семантики (TaskService.updateStatus, 5.1.2):
// индекс вставки считается в колонке ПОСЛЕ удаления перетаскиваемой задачи. Раз backend
// после каждого успешного move перенумеровывает колонку 0..n-1, task.position уже и есть
// её индекс в отсортированном списке — отдельно пересчитывать текущий индекс не нужно.
function computeDropTarget(tasksByStatus, activeTask, over) {
  if (!over) {
    return null
  }
  const overData = over.data.current
  const destStatus = overData?.status
  if (!destStatus) {
    return null
  }

  const destColumnWithoutActive = tasksByStatus[destStatus].filter((task) => task.id !== activeTask.id)
  let index
  if (overData.type === 'column' || over.id === activeTask.id) {
    index = destColumnWithoutActive.length
  } else {
    const overIndex = destColumnWithoutActive.findIndex((task) => task.id === over.id)
    index = overIndex === -1 ? destColumnWithoutActive.length : overIndex
  }

  if (destStatus === activeTask.status && index === activeTask.position) {
    return null
  }

  return { taskId: activeTask.id, status: destStatus, position: index, expectedStatus: activeTask.status }
}

// Содержимое карточки вынесено отдельно, чтобы карточка в колонке и карточка под
// курсором (DragOverlay) не разъезжались при правках вёрстки.
function TaskCardBody({ task, t, locale }) {
  const overdue = isTaskOverdue(task)
  const hours = formatHours(task.totalHoursSpent)

  return (
    <>
      <div className="flex items-start gap-2">
        <span className={TASK_NUMBER_BADGE_CLASS}>#{task.taskNumber}</span>
        {task.urgency !== 'MEDIUM' && (
          <span
            className={`ml-auto shrink-0 rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap ${taskUrgencyBadgeClass(task.urgency)}`}
          >
            {t(`urgency.${task.urgency}`)}
          </span>
        )}
      </div>

      <p
        title={task.title}
        className="mt-1.5 line-clamp-3 text-sm font-medium text-gray-900 dark:text-gray-100"
      >
        {task.title}
      </p>

      {(task.tag || task.category || task.dueDate) && (
        <div className="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1">
          {task.category && (
            <span
              title={t('tasks.detail.categoryLabel')}
              className="max-w-full truncate rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600 dark:bg-gray-700 dark:text-gray-300"
            >
              {task.category.name}
            </span>
          )}
          {task.tag && (
            <span
              className="max-w-full truncate rounded-full px-2 py-0.5 text-xs font-medium"
              style={tagBadgeStyle(task.tag.color)}
            >
              {task.tag.name}
            </span>
          )}
          {task.dueDate && (
            <span
              title={overdue ? t('tasks.overdue') : t('tasks.detail.dueDateLabel')}
              className={`flex items-center gap-1 text-xs whitespace-nowrap ${
                overdue ? 'font-medium text-red-600 dark:text-red-400' : 'text-gray-500 dark:text-gray-400'
              }`}
            >
              <CalendarClock className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {formatDueDate(task.dueDate, locale)}
            </span>
          )}
        </div>
      )}

      <div className="mt-2 flex items-center gap-2 border-t border-gray-100 pt-2 dark:border-gray-700">
        <UserAvatar user={task.assignee} sizeClass="h-6 w-6" />
        <span className="min-w-0 flex-1 truncate text-xs text-gray-600 dark:text-gray-400">
          {task.assignee ? assigneeLabelOf(task) : t('tasks.unassigned')}
        </span>
        {hours && (
          <span
            title={t('tasks.detail.hoursSpentLabel')}
            className="flex shrink-0 items-center gap-1 text-xs tabular-nums text-gray-500 dark:text-gray-400"
          >
            <Clock3 className="h-3.5 w-3.5" aria-hidden="true" />
            {hours}
          </span>
        )}
      </div>
    </>
  )
}

function TaskCard({ task, disabled, onOpen, t, locale }) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: task.id,
    data: { type: 'task', status: task.status },
    disabled,
  })
  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.4 : 1,
  }

  return (
    <div
      ref={setNodeRef}
      style={style}
      {...attributes}
      {...listeners}
      onClick={onOpen}
      className="block w-full cursor-pointer rounded-md border border-gray-200 bg-white p-2.5 text-left hover:border-purple-300 hover:bg-purple-50 dark:border-gray-700 dark:bg-gray-800 dark:hover:border-purple-700 dark:hover:bg-purple-950/30"
    >
      <TaskCardBody task={task} t={t} locale={locale} />
    </div>
  )
}

function TaskCardOverlay({ task, t, locale }) {
  return (
    <div className="block w-full rounded-md border border-purple-300 bg-white p-2.5 text-left shadow-lg dark:border-purple-700 dark:bg-gray-800">
      <TaskCardBody task={task} t={t} locale={locale} />
    </div>
  )
}

function KanbanColumn({ status, tasks, disabled, onOpenTask, t, locale }) {
  const { setNodeRef } = useDroppable({ id: `column-${status}`, data: { type: 'column', status } })

  return (
    <div className="flex min-w-64 flex-1 flex-col overflow-hidden rounded-lg border border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-900/40">
      <div className={`h-1 shrink-0 ${taskStatusAccentClass(status)}`} aria-hidden="true" />
      <div className="flex shrink-0 items-center justify-between border-b border-gray-200 px-3 py-2 dark:border-gray-700">
        <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${taskStatusBadgeClass(status)}`}>
          {t(`tasks.status.${status}`)}
        </span>
        <span className="rounded-full bg-gray-200 px-2 py-0.5 text-xs font-medium text-gray-600 tabular-nums dark:bg-gray-700 dark:text-gray-300">
          {tasks.length}
        </span>
      </div>

      {/* Скроллится каждая колонка отдельно: длинная колонка не растягивает доску,
          и все шесть статусов всегда видны целиком. */}
      <div ref={setNodeRef} className="min-h-0 flex-1 space-y-2 overflow-y-auto p-2">
        <SortableContext items={tasks.map((task) => task.id)} strategy={verticalListSortingStrategy}>
          {tasks.length === 0 && (
            <p className="px-1 py-2 text-xs text-gray-400 dark:text-gray-500">{t('tasks.columnEmpty')}</p>
          )}
          {tasks.map((task) => (
            <TaskCard
              key={task.id}
              task={task}
              disabled={disabled}
              onOpen={() => onOpenTask(task.taskNumber)}
              t={t}
              locale={locale}
            />
          ))}
        </SortableContext>
      </div>
    </div>
  )
}

export function ProjectTasksPage() {
  const { t, i18n } = useTranslation()
  const { projectSlug } = useParams()
  const navigate = useNavigate()
  const currentUser = useAuthStore((state) => state.user)
  const [activeTaskId, setActiveTaskId] = useState(null)

  const { data: project } = useProjectBySlug(projectSlug)
  const projectId = project?.id
  const { data: members } = useProjectMembers(projectId)
  const { data: tasks, isLoading, isError, error } = useTasks(projectId)
  const createTask = useCreateTask(projectId)
  const updateTaskStatus = useUpdateTaskStatus(projectId)

  const myMembership = members?.find((member) => member.userId === currentUser?.id)
  const canManage = myMembership ? roleIsAtLeast(myMembership.role, 'MEMBER') : false

  const schema = useMemo(() => buildCreateTaskSchema(t), [i18n.language, t])
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm({ resolver: zodResolver(schema), defaultValues: { title: '' } })

  function onCreate(values) {
    createTask.mutate(values, { onSuccess: () => reset({ title: '' }) })
  }

  const tasksByStatus = useMemo(() => {
    const grouped = Object.fromEntries(TASK_STATUSES.map((status) => [status, []]))
    for (const task of tasks ?? []) {
      grouped[task.status]?.push(task)
    }
    return grouped
  }, [tasks])

  const activeTask = activeTaskId ? (tasks ?? []).find((task) => task.id === activeTaskId) : null

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )

  function handleDragStart(event) {
    setActiveTaskId(event.active.id)
  }

  function handleDragEnd(event) {
    setActiveTaskId(null)
    const draggedTask = (tasks ?? []).find((task) => task.id === event.active.id)
    if (!draggedTask) {
      return
    }
    const target = computeDropTarget(tasksByStatus, draggedTask, event.over)
    if (!target) {
      return
    }
    updateTaskStatus.mutate(target)
  }

  function handleDragCancel() {
    setActiveTaskId(null)
  }

  return (
    <div className="flex h-full flex-col gap-3 px-4 py-4">
      {canManage && (
        // Форма быстрого создания — одной строкой: доске нужна вся высота, а не карточка
        // с заголовком и подписью поля на всю ширину.
        <form onSubmit={handleSubmit(onCreate)} className="flex flex-wrap items-center gap-2">
          <input
            type="text"
            className={`${inputClass} w-full max-w-md`}
            placeholder={t('tasks.newTaskPlaceholder')}
            aria-label={t('tasks.newTaskLabel')}
            {...register('title')}
          />
          <button type="submit" disabled={createTask.isPending} className={primaryButtonClass}>
            {createTask.isPending ? t('tasks.adding') : t('tasks.add')}
          </button>
          {errors.title?.message && (
            <span className="text-sm text-red-600 dark:text-red-400">{errors.title.message}</span>
          )}
        </form>
      )}

      {createTask.isError && (
        <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(createTask.error, t)}</p>
      )}
      {updateTaskStatus.isError && (
        <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(updateTaskStatus.error, t)}</p>
      )}

      {isLoading && <p className="text-gray-500 dark:text-gray-400">{t('tasks.loading')}</p>}
      {isError && <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(error, t)}</p>}

      {!isLoading && !isError && (
        <DndContext
          sensors={sensors}
          collisionDetection={closestCorners}
          onDragStart={handleDragStart}
          onDragEnd={handleDragEnd}
          onDragCancel={handleDragCancel}
        >
          {/* Колонки делят всю ширину поровну (flex-1) и не сжимаются уже min-w-64 —
              на узком экране доска скроллится по горизонтали вместо переноса в сетку. */}
          <div className="flex min-h-0 flex-1 gap-3 overflow-x-auto pb-1">
            {TASK_STATUSES.map((status) => (
              <KanbanColumn
                key={status}
                status={status}
                tasks={tasksByStatus[status]}
                disabled={!canManage}
                onOpenTask={(taskNumber) => navigate(`/projects/${projectSlug}/tasks/${taskNumber}`)}
                t={t}
                locale={i18n.language}
              />
            ))}
          </div>

          <DragOverlay>
            {activeTask && <TaskCardOverlay task={activeTask} t={t} locale={i18n.language} />}
          </DragOverlay>
        </DndContext>
      )}
    </div>
  )
}
