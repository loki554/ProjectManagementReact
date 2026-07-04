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
import { useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { z } from 'zod'
import { useCreateTask, useTasks, useUpdateTaskStatus } from '../../api/tasksQueries'
import { useProject, useProjectMembers } from '../../api/projectsQueries'
import { AppHeader } from '../../components/layout/AppHeader'
import { Field, inputClass, primaryButtonClass } from '../../components/ui/FormKit'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { TASK_NUMBER_BADGE_CLASS, TASK_STATUSES, roleIsAtLeast, taskStatusBadgeClass } from '../../lib/constants'
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

function TaskCard({ task, disabled, onOpen }) {
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
      className="block w-full cursor-pointer rounded-md border border-gray-200 bg-white p-2 text-left text-sm hover:border-purple-300 hover:bg-purple-50"
    >
      <p className="flex items-center gap-2 font-medium text-gray-900">
        <span className={TASK_NUMBER_BADGE_CLASS}>#{task.taskNumber}</span>
        <span className="min-w-0 truncate">{task.title}</span>
      </p>
      <p className="mt-1 text-xs text-gray-500">{task.assigneeLabel}</p>
    </div>
  )
}

function TaskCardOverlay({ task }) {
  return (
    <div className="block w-full rounded-md border border-purple-300 bg-white p-2 text-left text-sm shadow-lg">
      <p className="flex items-center gap-2 font-medium text-gray-900">
        <span className={TASK_NUMBER_BADGE_CLASS}>#{task.taskNumber}</span>
        <span className="min-w-0 truncate">{task.title}</span>
      </p>
      <p className="mt-1 text-xs text-gray-500">{task.assigneeLabel}</p>
    </div>
  )
}

function KanbanColumn({ status, tasks, disabled, onOpenTask, t }) {
  const { setNodeRef } = useDroppable({ id: `column-${status}`, data: { type: 'column', status } })

  return (
    <div className="rounded-lg border border-gray-200 bg-white">
      <div className="flex items-center justify-between border-b border-gray-100 px-3 py-2">
        <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${taskStatusBadgeClass(status)}`}>
          {t(`tasks.status.${status}`)}
        </span>
        <span className="text-xs text-gray-400">{tasks.length}</span>
      </div>

      <div ref={setNodeRef} className="min-h-16 space-y-2 p-2">
        <SortableContext items={tasks.map((task) => task.id)} strategy={verticalListSortingStrategy}>
          {tasks.length === 0 && <p className="px-1 py-2 text-xs text-gray-400">{t('tasks.columnEmpty')}</p>}
          {tasks.map((task) => (
            <TaskCard key={task.id} task={task} disabled={disabled} onOpen={() => onOpenTask(task.id)} />
          ))}
        </SortableContext>
      </div>
    </div>
  )
}

export function ProjectTasksPage() {
  const { t, i18n } = useTranslation()
  const { projectId } = useParams()
  const navigate = useNavigate()
  const currentUser = useAuthStore((state) => state.user)
  const [activeTaskId, setActiveTaskId] = useState(null)

  const { data: project } = useProject(projectId)
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
      grouped[task.status]?.push({
        ...task,
        assigneeLabel: task.assignee
          ? `${task.assignee.lastName} ${task.assignee.firstName}`
          : t('tasks.unassigned'),
      })
    }
    return grouped
  }, [tasks, t])

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
    <div className="min-h-svh bg-gray-50">
      <AppHeader />

      <div className="mx-auto max-w-7xl px-4 py-8">
        <Link to="/projects" className="text-sm text-purple-600 hover:underline">
          {t('projects.backToList')}
        </Link>

        <div className="mt-4 mb-6 flex flex-wrap items-center justify-between gap-3">
          <h1 className="text-2xl font-semibold text-gray-900">
            {t('tasks.title', { project: project?.name ?? '' })}
          </h1>
          <Link
            to={`/projects/${projectId}/settings/members`}
            className="text-sm text-purple-600 hover:underline"
          >
            {t('projects.members')}
          </Link>
        </div>

        {canManage && (
          <form
            onSubmit={handleSubmit(onCreate)}
            className="mb-6 flex flex-wrap items-end gap-3 rounded-lg border border-gray-200 bg-white p-4"
          >
            <div className="min-w-64 flex-1">
              <Field label={t('tasks.newTaskLabel')} error={errors.title?.message}>
                <input
                  type="text"
                  className={inputClass}
                  placeholder={t('tasks.newTaskPlaceholder')}
                  {...register('title')}
                />
              </Field>
            </div>
            <button type="submit" disabled={createTask.isPending} className={primaryButtonClass}>
              {createTask.isPending ? t('tasks.adding') : t('tasks.add')}
            </button>
          </form>
        )}

        {createTask.isError && (
          <p className="mb-4 text-sm text-red-600">{getLocalizedErrorMessage(createTask.error, t)}</p>
        )}
        {updateTaskStatus.isError && (
          <p className="mb-4 text-sm text-red-600">{getLocalizedErrorMessage(updateTaskStatus.error, t)}</p>
        )}

        {isLoading && <p className="text-gray-500">{t('tasks.loading')}</p>}
        {isError && <p className="text-sm text-red-600">{getLocalizedErrorMessage(error, t)}</p>}

        {!isLoading && !isError && (
          <DndContext
            sensors={sensors}
            collisionDetection={closestCorners}
            onDragStart={handleDragStart}
            onDragEnd={handleDragEnd}
            onDragCancel={handleDragCancel}
          >
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
              {TASK_STATUSES.map((status) => (
                <KanbanColumn
                  key={status}
                  status={status}
                  tasks={tasksByStatus[status]}
                  disabled={!canManage}
                  onOpenTask={(taskId) => navigate(`/projects/${projectId}/tasks/${taskId}`)}
                  t={t}
                />
              ))}
            </div>

            <DragOverlay>{activeTask && <TaskCardOverlay task={activeTask} />}</DragOverlay>
          </DndContext>
        )}
      </div>
    </div>
  )
}
