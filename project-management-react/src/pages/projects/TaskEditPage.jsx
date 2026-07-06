import { zodResolver } from '@hookform/resolvers/zod'
import { useMemo } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom'
import { z } from 'zod'
import { useProjectBySlug, useProjectMembers } from '../../api/projectsQueries'
import { useTags } from '../../api/tagsQueries'
import { useDeleteTask, useTaskByNumber, useUpdateTask } from '../../api/tasksQueries'
import { MarkdownEditor } from '../../components/markdown/MarkdownEditor'
import { Field, inputClass, primaryButtonClass, secondaryButtonClass } from '../../components/ui/FormKit'
import {
  TASK_NUMBER_BADGE_CLASS,
  TASK_STATUSES,
  TASK_URGENCIES,
  roleIsAtLeast,
} from '../../lib/constants'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { fromDatetimeLocalValue, toDatetimeLocalValue } from '../../lib/datetimeLocal'
import { useAuthStore } from '../../stores/authStore'

function buildTaskSchema(t) {
  return z.object({
    title: z.string().min(1, t('auth.validation.required')).max(255),
    description: z.string().optional(),
    status: z.enum(TASK_STATUSES),
    assigneeId: z.string().optional(),
    urgency: z.enum(TASK_URGENCIES),
    dueDate: z.string().optional(),
    tagId: z.string().optional(),
  })
}

export function TaskEditPage() {
  const { t, i18n } = useTranslation()
  const { projectSlug, taskNumber } = useParams()
  const navigate = useNavigate()
  const currentUser = useAuthStore((state) => state.user)

  const { data: project, isLoading: projectLoading } = useProjectBySlug(projectSlug)
  const projectId = project?.id
  const {
    data: task,
    isLoading: taskLoading,
    isError,
    error,
  } = useTaskByNumber(projectId, taskNumber)
  const taskId = task?.id
  const { data: members } = useProjectMembers(projectId)
  const { data: tags } = useTags(projectId)
  const isLoading = projectLoading || taskLoading || !members

  const updateTask = useUpdateTask(taskId)
  const deleteTask = useDeleteTask(projectId)

  const myMembership = members?.find((member) => member.userId === currentUser?.id)
  const canManage = myMembership ? roleIsAtLeast(myMembership.role, 'MEMBER') : false

  const viewPath = `/projects/${projectSlug}/tasks/${taskNumber}`

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
          urgency: task.urgency,
          dueDate: toDatetimeLocalValue(task.dueDate),
          tagId: task.tag?.id ?? '',
        }
      : undefined,
  })

  function onSave(values) {
    updateTask.mutate(
      {
        title: values.title,
        description: values.description || null,
        status: values.status,
        assigneeId: values.assigneeId || null,
        urgency: values.urgency,
        dueDate: fromDatetimeLocalValue(values.dueDate),
        tagId: values.tagId || null,
      },
      { onSuccess: () => navigate(viewPath) },
    )
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
              ? `/projects/${projectSlug}/tasks/${task.parentTaskNumber}`
              : `/projects/${projectSlug}/board`,
          )
        },
      },
    )
  }

  // Редирект — только после загрузки members: до этого canManage ещё "ложно-false"
  // и легитимного участника отбросило бы на просмотр. Серверный PATCH и так защищён.
  if (!isLoading && !isError && !canManage) {
    return <Navigate to={viewPath} replace />
  }

  return (
    <div className="mx-auto max-w-3xl px-4 py-8">
      <Link to={viewPath} className="text-sm text-purple-600 hover:underline">
        {t('tasks.detail.backToTask')}
      </Link>

      {isLoading && <p className="mt-4 text-gray-500">{t('tasks.detail.loading')}</p>}
      {isError && <p className="mt-4 text-sm text-red-600">{getLocalizedErrorMessage(error, t)}</p>}

      {!isLoading && !isError && task && (
        <div className="mt-4 rounded-lg border border-gray-200 bg-white p-6">
          <form onSubmit={handleSubmit(onSave)} className="space-y-4">
            <div>
              <span className={TASK_NUMBER_BADGE_CLASS}>#{task.taskNumber}</span>
            </div>
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

            <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
              <Field label={t('tasks.detail.urgencyLabel')}>
                <select className={inputClass} {...register('urgency')}>
                  {TASK_URGENCIES.map((urgency) => (
                    <option key={urgency} value={urgency}>
                      {t(`urgency.${urgency}`)}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label={t('tasks.detail.dueDateLabel')}>
                <input type="datetime-local" className={inputClass} {...register('dueDate')} />
              </Field>
              <Field label={t('tasks.detail.tagLabel')}>
                <select className={inputClass} {...register('tagId')}>
                  <option value="">{t('tasks.noTag')}</option>
                  {tags?.map((tag) => (
                    <option key={tag.id} value={tag.id}>
                      {tag.name}
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

            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <button type="submit" disabled={updateTask.isPending || !isDirty} className={primaryButtonClass}>
                  {updateTask.isPending ? t('tasks.detail.saving') : t('tasks.detail.save')}
                </button>
                <Link to={viewPath} className={secondaryButtonClass}>
                  {t('tasks.detail.cancel')}
                </Link>
              </div>
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
        </div>
      )}
    </div>
  )
}
