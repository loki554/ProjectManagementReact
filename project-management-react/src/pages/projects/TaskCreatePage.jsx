import { zodResolver } from '@hookform/resolvers/zod'
import { useMemo } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link, Navigate, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { z } from 'zod'
import { useProjectBySlug, useProjectMembers } from '../../api/projectsQueries'
import { useTags } from '../../api/tagsQueries'
import { useCreateSubtask, useCreateTask, useTaskByNumber } from '../../api/tasksQueries'
import { MarkdownEditor } from '../../components/markdown/MarkdownEditor'
import { Field, inputClass, primaryButtonClass, secondaryButtonClass } from '../../components/ui/FormKit'
import {
  TASK_NUMBER_BADGE_CLASS,
  TASK_STATUSES,
  TASK_URGENCIES,
  roleIsAtLeast,
} from '../../lib/constants'
import { fromDatetimeLocalValue } from '../../lib/datetimeLocal'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { useAuthStore } from '../../stores/authStore'

// Схема идентична TaskEditPage — форма создания оперирует тем же набором полей
// (бэкенд принимает один и тот же CreateTaskRequest и для задач, и для подзадач).
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

// Одна страница на оба случая: /tasks/new — top-level задача,
// /tasks/new?parent=<taskNumber> — подзадача указанного родителя (номер, не UUID,
// в духе остальных читаемых URL-ов /projects/:slug/tasks/:taskNumber).
export function TaskCreatePage() {
  const { t, i18n } = useTranslation()
  const { projectSlug } = useParams()
  const [searchParams] = useSearchParams()
  const parentNumber = searchParams.get('parent')
  const navigate = useNavigate()
  const currentUser = useAuthStore((state) => state.user)

  const { data: project, isLoading: projectLoading } = useProjectBySlug(projectSlug)
  const projectId = project?.id
  const { data: members } = useProjectMembers(projectId)
  const { data: tags } = useTags(projectId)
  const {
    data: parentTask,
    isLoading: parentLoading,
    isError: parentIsError,
    error: parentError,
  } = useTaskByNumber(projectId, parentNumber)
  const isLoading = projectLoading || !members || (Boolean(parentNumber) && parentLoading)

  const createTask = useCreateTask(projectId)
  const createSubtask = useCreateSubtask(parentTask?.id)
  const activeMutation = parentNumber ? createSubtask : createTask

  const myMembership = members?.find((member) => member.userId === currentUser?.id)
  const canManage = myMembership ? roleIsAtLeast(myMembership.role, 'MEMBER') : false

  const listPath = `/projects/${projectSlug}/tasks`
  const backPath = parentNumber ? `/projects/${projectSlug}/tasks/${parentNumber}` : listPath

  const schema = useMemo(() => buildTaskSchema(t), [i18n.language, t])
  const {
    register,
    control,
    handleSubmit,
    formState: { errors },
  } = useForm({
    resolver: zodResolver(schema),
    defaultValues: {
      title: '',
      description: '',
      status: 'NEW',
      assigneeId: '',
      urgency: 'MEDIUM',
      dueDate: '',
      tagId: '',
    },
  })

  function onCreate(values) {
    activeMutation.mutate(
      {
        title: values.title,
        description: values.description || null,
        status: values.status,
        assigneeId: values.assigneeId || null,
        urgency: values.urgency,
        dueDate: fromDatetimeLocalValue(values.dueDate),
        tagId: values.tagId || null,
      },
      { onSuccess: (created) => navigate(`/projects/${projectSlug}/tasks/${created.taskNumber}`) },
    )
  }

  // Как в TaskEditPage: редирект только после загрузки members, иначе легитимного
  // участника выбросило бы, пока canManage ещё "ложно-false". Серверный POST и так защищён.
  if (!isLoading && !parentIsError && !canManage) {
    return <Navigate to={listPath} replace />
  }

  return (
    <div className="mx-auto max-w-3xl px-4 py-8">
      <Link to={backPath} className="text-sm text-purple-600 hover:underline">
        {parentNumber ? t('tasks.detail.backToParent') : t('tasks.create.backToList')}
      </Link>

      {isLoading && <p className="mt-4 text-gray-500">{t('app.loading')}</p>}
      {parentIsError && (
        <p className="mt-4 text-sm text-red-600">{getLocalizedErrorMessage(parentError, t)}</p>
      )}

      {!isLoading && !parentIsError && (
        <div className="mt-4 rounded-lg border border-gray-200 bg-white p-6">
          <h1 className="text-lg font-semibold text-gray-900">
            {parentNumber ? t('tasks.create.subtaskTitle') : t('tasks.create.title')}
          </h1>

          {parentTask && (
            <p className="mt-2 text-sm text-gray-500">
              {t('tasks.create.parentLabel')}:{' '}
              <Link to={backPath} className="text-purple-700 hover:underline">
                <span className={TASK_NUMBER_BADGE_CLASS}>#{parentTask.taskNumber}</span>{' '}
                {parentTask.title}
              </Link>
            </p>
          )}

          <form onSubmit={handleSubmit(onCreate)} className="mt-4 space-y-4">
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

            {activeMutation.isError && (
              <p className="text-sm text-red-600">
                {getLocalizedErrorMessage(activeMutation.error, t)}
              </p>
            )}

            <div className="flex items-center gap-3">
              <button type="submit" disabled={activeMutation.isPending} className={primaryButtonClass}>
                {activeMutation.isPending ? t('tasks.create.creating') : t('tasks.create.submit')}
              </button>
              <Link to={backPath} className={secondaryButtonClass}>
                {t('tasks.detail.cancel')}
              </Link>
            </div>
          </form>
        </div>
      )}
    </div>
  )
}
