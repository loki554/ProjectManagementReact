import { zodResolver } from '@hookform/resolvers/zod'
import { useMemo } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { z } from 'zod'
import { useProjectMembers } from '../../api/projectsQueries'
import { useCreateSubtask, useDeleteTask, useSubtasks, useTask, useUpdateTask } from '../../api/tasksQueries'
import { AppHeader } from '../../components/layout/AppHeader'
import { MarkdownEditor } from '../../components/markdown/MarkdownEditor'
import { MarkdownRenderer } from '../../components/markdown/MarkdownRenderer'
import { Field, inputClass, primaryButtonClass } from '../../components/ui/FormKit'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { TASK_STATUSES, roleIsAtLeast, taskStatusBadgeClass } from '../../lib/constants'
import { useAuthStore } from '../../stores/authStore'

function buildTaskSchema(t) {
  return z.object({
    title: z.string().min(1, t('auth.validation.required')).max(255),
    description: z.string().optional(),
    status: z.enum(TASK_STATUSES),
    assigneeId: z.string().optional(),
  })
}

function buildSubtaskSchema(t) {
  return z.object({
    title: z.string().min(1, t('auth.validation.required')).max(255),
  })
}

export function TaskDetailPage() {
  const { t, i18n } = useTranslation()
  const { projectId, taskId } = useParams()
  const navigate = useNavigate()
  const currentUser = useAuthStore((state) => state.user)

  const { data: task, isLoading, isError, error } = useTask(taskId)
  const { data: members } = useProjectMembers(projectId)
  const {
    data: subtasks,
    isLoading: subtasksLoading,
    isError: subtasksIsError,
    error: subtasksError,
  } = useSubtasks(taskId)

  const updateTask = useUpdateTask(taskId)
  const deleteTask = useDeleteTask(projectId)
  const createSubtask = useCreateSubtask(taskId)

  const myMembership = members?.find((member) => member.userId === currentUser?.id)
  const canManage = myMembership ? roleIsAtLeast(myMembership.role, 'MEMBER') : false

  const schema = useMemo(() => buildTaskSchema(t), [i18n.language, t])
  const {
    register,
    control,
    handleSubmit,
    formState: { errors, isDirty },
  } = useForm({
    resolver: zodResolver(schema),
    // values (не defaultValues) держит форму синхронизированной с кэшем react-query —
    // тот же приём, что и в ProfilePage.
    values: task
      ? {
          title: task.title,
          description: task.description ?? '',
          status: task.status,
          assigneeId: task.assignee?.id ?? '',
        }
      : undefined,
  })

  const subtaskSchema = useMemo(() => buildSubtaskSchema(t), [i18n.language, t])
  const {
    register: registerSubtask,
    handleSubmit: handleSubmitSubtask,
    reset: resetSubtask,
    formState: { errors: subtaskErrors },
  } = useForm({ resolver: zodResolver(subtaskSchema), defaultValues: { title: '' } })

  function onSave(values) {
    updateTask.mutate({
      title: values.title,
      description: values.description || null,
      status: values.status,
      assigneeId: values.assigneeId || null,
    })
  }

  function onDelete() {
    if (!window.confirm(t('tasks.detail.deleteConfirm'))) {
      return
    }
    deleteTask.mutate(
      { taskId, parentTaskId: task.parentTaskId },
      {
        onSuccess: () => {
          navigate(
            task.parentTaskId
              ? `/projects/${projectId}/tasks/${task.parentTaskId}`
              : `/projects/${projectId}/board`,
          )
        },
      },
    )
  }

  function onCreateSubtask(values) {
    createSubtask.mutate(values, { onSuccess: () => resetSubtask({ title: '' }) })
  }

  const backLink = task?.parentTaskId
    ? `/projects/${projectId}/tasks/${task.parentTaskId}`
    : `/projects/${projectId}/board`

  return (
    <div className="min-h-svh bg-gray-50">
      <AppHeader />

      <div className="mx-auto max-w-3xl px-4 py-8">
        <Link to={backLink} className="text-sm text-purple-600 hover:underline">
          {t(task?.parentTaskId ? 'tasks.detail.backToParent' : 'tasks.detail.backToBoard')}
        </Link>

        {isLoading && <p className="mt-4 text-gray-500">{t('tasks.detail.loading')}</p>}
        {isError && <p className="mt-4 text-sm text-red-600">{getLocalizedErrorMessage(error, t)}</p>}

        {!isLoading && !isError && task && (
          <div className="mt-4 rounded-lg border border-gray-200 bg-white p-6">
            {canManage ? (
              <form onSubmit={handleSubmit(onSave)} className="space-y-4">
                <Field label={t('tasks.detail.titleLabel')} error={errors.title?.message}>
                  <input type="text" className={inputClass} {...register('title')} />
                </Field>

                <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                  <Field label={t('tasks.detail.statusLabel')}>
                    <select className={inputClass} {...register('status')}>
                      {TASK_STATUSES.map((status) => (
                        <option key={status} value={status}>
                          {t(`tasks.status.${status}`)}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label={t('tasks.detail.assigneeLabel')}>
                    <select className={inputClass} {...register('assigneeId')}>
                      <option value="">{t('tasks.unassigned')}</option>
                      {members?.map((member) => (
                        <option key={member.userId} value={member.userId}>
                          {member.lastName} {member.firstName}
                        </option>
                      ))}
                    </select>
                  </Field>
                </div>

                <Controller
                  name="description"
                  control={control}
                  render={({ field }) => (
                    <Field label={t('tasks.detail.descriptionLabel')}>
                      <MarkdownEditor value={field.value} onChange={field.onChange} />
                    </Field>
                  )}
                />

                {updateTask.isError && (
                  <p className="text-sm text-red-600">{getLocalizedErrorMessage(updateTask.error, t)}</p>
                )}
                {updateTask.isSuccess && !isDirty && (
                  <p className="text-sm text-green-700">{t('tasks.detail.saved')}</p>
                )}

                <div className="flex items-center justify-between">
                  <button type="submit" disabled={updateTask.isPending || !isDirty} className={primaryButtonClass}>
                    {updateTask.isPending ? t('tasks.detail.saving') : t('tasks.detail.save')}
                  </button>
                  <button
                    type="button"
                    onClick={onDelete}
                    disabled={deleteTask.isPending}
                    className="text-sm text-red-600 hover:underline disabled:opacity-60"
                  >
                    {deleteTask.isPending ? t('tasks.detail.deleting') : t('tasks.detail.delete')}
                  </button>
                </div>
                {deleteTask.isError && (
                  <p className="text-sm text-red-600">{getLocalizedErrorMessage(deleteTask.error, t)}</p>
                )}
              </form>
            ) : (
              <div className="space-y-4">
                <h1 className="text-xl font-semibold text-gray-900">{task.title}</h1>
                <div className="flex flex-wrap items-center gap-3 text-sm text-gray-600">
                  <span
                    className={`rounded-full px-2 py-0.5 text-xs font-medium ${taskStatusBadgeClass(task.status)}`}
                  >
                    {t(`tasks.status.${task.status}`)}
                  </span>
                  <span>
                    {task.assignee
                      ? `${task.assignee.lastName} ${task.assignee.firstName}`
                      : t('tasks.unassigned')}
                  </span>
                </div>
                {task.description ? (
                  <MarkdownRenderer>{task.description}</MarkdownRenderer>
                ) : (
                  <p className="text-sm text-gray-400">{t('tasks.detail.noDescription')}</p>
                )}
              </div>
            )}
          </div>
        )}

        {!isLoading && !isError && task && (
          <div className="mt-6 rounded-lg border border-gray-200 bg-white p-6">
            <h2 className="mb-3 text-sm font-semibold text-gray-900">{t('tasks.subtasks.title')}</h2>

            {subtasksLoading && <p className="text-sm text-gray-500">{t('tasks.subtasks.loading')}</p>}
            {subtasksIsError && (
              <p className="text-sm text-red-600">{getLocalizedErrorMessage(subtasksError, t)}</p>
            )}

            {!subtasksLoading && !subtasksIsError && (
              <ul className="mb-4 divide-y divide-gray-100">
                {subtasks.length === 0 && (
                  <li className="py-2 text-sm text-gray-400">{t('tasks.subtasks.empty')}</li>
                )}
                {subtasks.map((subtask) => (
                  <li key={subtask.id}>
                    <Link
                      to={`/projects/${projectId}/tasks/${subtask.id}`}
                      className="flex items-center justify-between gap-3 py-2 text-sm hover:text-purple-700"
                    >
                      <span className="text-gray-900">{subtask.title}</span>
                      <span
                        className={`rounded-full px-2 py-0.5 text-xs font-medium ${taskStatusBadgeClass(subtask.status)}`}
                      >
                        {t(`tasks.status.${subtask.status}`)}
                      </span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}

            {canManage && (
              <form onSubmit={handleSubmitSubtask(onCreateSubtask)} className="flex items-start gap-3">
                <div className="min-w-48 flex-1">
                  <input
                    type="text"
                    className={inputClass}
                    placeholder={t('tasks.subtasks.addPlaceholder')}
                    {...registerSubtask('title')}
                  />
                  {subtaskErrors.title && (
                    <p className="mt-1 text-xs text-red-600">{subtaskErrors.title.message}</p>
                  )}
                </div>
                <button type="submit" disabled={createSubtask.isPending} className={primaryButtonClass}>
                  {createSubtask.isPending ? t('tasks.subtasks.adding') : t('tasks.subtasks.add')}
                </button>
              </form>
            )}
            {createSubtask.isError && (
              <p className="mt-2 text-sm text-red-600">{getLocalizedErrorMessage(createSubtask.error, t)}</p>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
