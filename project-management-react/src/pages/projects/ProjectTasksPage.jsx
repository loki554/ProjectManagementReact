import { zodResolver } from '@hookform/resolvers/zod'
import { useMemo } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { z } from 'zod'
import { useCreateTask, useTasks } from '../../api/tasksQueries'
import { useProject, useProjectMembers } from '../../api/projectsQueries'
import { AppHeader } from '../../components/layout/AppHeader'
import { Field, inputClass, primaryButtonClass } from '../../components/ui/FormKit'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { TASK_STATUSES, roleIsAtLeast, taskStatusBadgeClass } from '../../lib/constants'
import { useAuthStore } from '../../stores/authStore'

function buildCreateTaskSchema(t) {
  return z.object({
    title: z.string().min(1, t('auth.validation.required')).max(255),
  })
}

// Временный список задач вместо настоящего канбана (Phase 5, см. 4.6.2 плана) — 6 колонок
// по фиксированным статусам, без drag-n-drop. Смена статуса/исполнителя — на TaskDetailPage,
// эта страница только показывает и заводит новые задачи (быстрое добавление — только title,
// остальные поля правятся уже в деталях задачи).
export function ProjectTasksPage() {
  const { t, i18n } = useTranslation()
  const { projectId } = useParams()
  const navigate = useNavigate()
  const currentUser = useAuthStore((state) => state.user)

  const { data: project } = useProject(projectId)
  const { data: members } = useProjectMembers(projectId)
  const { data: tasks, isLoading, isError, error } = useTasks(projectId)
  const createTask = useCreateTask(projectId)

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

        {isLoading && <p className="text-gray-500">{t('tasks.loading')}</p>}
        {isError && <p className="text-sm text-red-600">{getLocalizedErrorMessage(error, t)}</p>}

        {!isLoading && !isError && (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
            {TASK_STATUSES.map((status) => (
              <div key={status} className="rounded-lg border border-gray-200 bg-white">
                <div className="flex items-center justify-between border-b border-gray-100 px-3 py-2">
                  <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${taskStatusBadgeClass(status)}`}>
                    {t(`tasks.status.${status}`)}
                  </span>
                  <span className="text-xs text-gray-400">{tasksByStatus[status].length}</span>
                </div>

                <div className="min-h-16 space-y-2 p-2">
                  {tasksByStatus[status].length === 0 && (
                    <p className="px-1 py-2 text-xs text-gray-400">{t('tasks.columnEmpty')}</p>
                  )}
                  {tasksByStatus[status].map((task) => (
                    <button
                      key={task.id}
                      type="button"
                      onClick={() => navigate(`/projects/${projectId}/tasks/${task.id}`)}
                      className="block w-full rounded-md border border-gray-200 p-2 text-left text-sm hover:border-purple-300 hover:bg-purple-50"
                    >
                      <p className="font-medium text-gray-900">{task.title}</p>
                      <p className="mt-1 text-xs text-gray-500">
                        {task.assignee
                          ? `${task.assignee.lastName} ${task.assignee.firstName}`
                          : t('tasks.unassigned')}
                      </p>
                    </button>
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
