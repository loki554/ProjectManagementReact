import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useMemo } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom'
import { z } from 'zod'
import { useProjectBySlug, useProjectMembers } from '../../api/projectsQueries'
import { useCategories } from '../../api/categoriesQueries'
import { useSprints } from '../../api/sprintsQueries'
import { useTags } from '../../api/tagsQueries'
import { fetchTaskDeletionSummary, useDeleteTask, useTaskByNumber, useUpdateTask } from '../../api/tasksQueries'
import { MarkdownEditor } from '../../components/markdown/MarkdownEditor'
import { TaskErrorNotice } from '../../components/tasks/TaskErrorNotice'
import { Combobox } from '../../components/ui/Combobox'
import { Field, inputClass, primaryButtonClass, secondaryButtonClass } from '../../components/ui/FormKit'
import {
  TASK_NUMBER_BADGE_CLASS,
  TASK_STATUSES,
  TASK_URGENCIES,
  canWriteInProject,
} from '../../lib/constants'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { isOpenBlockersError } from '../../lib/taskBlockers'
import { confirmDoneWithBlockers } from '../../lib/taskBlockers'
import { describeTaskDeletion } from '../../lib/taskDeletion'
import { confirmAction } from '../../stores/confirmStore'
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
    category: z.string().max(100).optional(),
    sprintId: z.string().optional(),
  })
}

export function TaskEditPage() {
  const { t, i18n } = useTranslation()
  const { projectSlug, taskNumber } = useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
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
  const { data: categories } = useCategories(projectId)
  const { data: sprints } = useSprints(projectId)
  // Combobox оперирует именами: в задаче категория задаётся свободным вводом, и бэкенд
  // сам сопоставляет имя со справочником (создавая недостающую запись).
  const categoryNames = useMemo(() => (categories ?? []).map((category) => category.name), [categories])
  const isLoading = projectLoading || taskLoading || !members

  const updateTask = useUpdateTask(taskId)
  const deleteTask = useDeleteTask(projectId)

  const myMembership = members?.find((member) => member.userId === currentUser?.id)
  const canManage = canWriteInProject(project, myMembership?.role, 'MEMBER')

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
          category: task.category?.name ?? '',
          sprintId: task.sprint?.id ?? '',
        }
      : undefined,
  })

  /**
   * Сохранение формы. Второй аргумент — согласие закрыть задачу с незакрытыми блокерами
   * (4.8): в первом запросе его нет, и если блокеры остались, сервер отвечает 409
   * TASK_HAS_OPEN_BLOCKERS. Тогда задаётся вопрос и тот же запрос уходит повторно.
   *
   * Спрашивать заранее, по openBlockerCount из загруженной задачи, было бы дешевле, но
   * форма живёт открытой сколько угодно: за это время блокер могли и закрыть, и завести.
   * Отказ от сервера всегда про то, как обстоят дела сейчас.
   */
  function save(values, ignoreBlockers) {
    updateTask.mutate(
      {
        title: values.title,
        description: values.description || null,
        status: values.status,
        assigneeId: values.assigneeId || null,
        urgency: values.urgency,
        dueDate: fromDatetimeLocalValue(values.dueDate),
        tagId: values.tagId || null,
        category: values.category?.trim() || null,
        sprintId: values.sprintId || null,
        // Версия задачи на момент открытия формы (3.4): сервер ответит 409
        // CONCURRENT_MODIFICATION, если её успели изменить, вместо того чтобы молча
        // затереть чужую правку.
        version: task.version,
        ignoreBlockers,
      },
      {
        onSuccess: () => navigate(viewPath),
        onError: async (error) => {
          if (!ignoreBlockers && isOpenBlockersError(error) && (await confirmDoneWithBlockers(t))) {
            save(values, true)
          }
        },
      },
    )
  }

  function onSave(values) {
    save(values, false)
  }

  /**
   * Удаление задачи — единственное место, где вопрос сначала идёт на сервер (5.2): в нём
   * перечисляется, что именно уедет в корзину вместе с задачей. Без этого «удалить задачу»
   * и «удалить задачу с шестью подзадачами, перепиской и списанным временем» выглядели
   * одинаково, хотя это принципиально разные решения.
   *
   * Счётчики читаются по тем же данным, что и вкладки страницы задачи, поэтому у пришедшего
   * оттуда человека берутся из кэша. Если запрос не удался, вопрос всё равно задаётся, но
   * без подробностей: не показать состав — неприятно, а не спросить перед удалением — куда
   * хуже.
   */
  async function onDelete() {
    const summary = await fetchTaskDeletionSummary(queryClient, taskId).catch(() => null)
    const confirmed = await confirmAction({
      title: t('tasks.detail.deleteConfirm'),
      body: describeTaskDeletion(summary, t, i18n.language),
      confirmLabel: t('confirm.delete'),
    })
    if (!confirmed) {
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
      <Link to={viewPath} className="text-sm text-purple-600 hover:underline dark:text-purple-400">
        {t('tasks.detail.backToTask')}
      </Link>

      {isLoading && <p className="mt-4 text-gray-500 dark:text-gray-400">{t('tasks.detail.loading')}</p>}
      {isError && <TaskErrorNotice error={error} projectSlug={projectSlug} />}

      {!isLoading && !isError && task && (
        <div className="mt-4 rounded-lg border border-gray-200 bg-white p-6 dark:border-gray-700 dark:bg-gray-800">
          <form onSubmit={handleSubmit(onSave)} className="space-y-4">
            <div>
              <span className={TASK_NUMBER_BADGE_CLASS}>#{task.taskNumber}</span>
            </div>
            <Field label={t('tasks.detail.titleLabel')} error={errors.title?.message}>
              <input type="text" className={inputClass} maxLength={255} {...register('title')} />
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

            {/* Спринт (4.9) — обычное поле задачи, как тэг. Завершённые спринты в списке
                есть только затем, чтобы задача, уже лежащая в таком, не теряла его при
                первой же правке; выбрать завершённый спринт заново сервер не даст. */}
            <Field label={t('tasks.detail.sprintLabel')}>
              <select className={inputClass} {...register('sprintId')}>
                <option value="">{t('tasks.noSprint')}</option>
                {sprints?.map((sprint) => (
                  <option key={sprint.id} value={sprint.id}>
                    {sprint.name}
                  </option>
                ))}
              </select>
            </Field>

            {/* Свободный текст с подсказками уже использованных в проекте категорий —
                справочника категорий (в отличие от тэгов) нет, значение вводится вручную.
                Controller, а не register: Combobox — контролируемый компонент. */}
            <Controller
              name="category"
              control={control}
              render={({ field }) => (
                <Field label={t('tasks.detail.categoryLabel')} error={errors.category?.message}>
                  <Combobox
                    value={field.value}
                    onChange={field.onChange}
                    options={categoryNames}
                    placeholder={t('tasks.detail.categoryPlaceholder')}
                    maxLength={100}
                    emptyText={t('tasks.detail.categoryEmpty')}
                    noMatchesText={t('tasks.detail.categoryNoMatches')}
                  />
                </Field>
              )}
            />

            <Controller
              name="description"
              control={control}
              render={({ field }) => (
                <Field label={t('tasks.detail.descriptionLabel')}>
                  <MarkdownEditor value={field.value} onChange={field.onChange} maxLength={20000} />
                </Field>
              )}
            />

            {updateTask.isError && (
              <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(updateTask.error, t)}</p>
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
                className="text-sm text-red-600 hover:underline disabled:opacity-60 dark:text-red-400"
              >
                {deleteTask.isPending ? t('tasks.detail.deleting') : t('tasks.detail.delete')}
              </button>
            </div>
            {deleteTask.isError && (
              <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(deleteTask.error, t)}</p>
            )}
          </form>
        </div>
      )}
    </div>
  )
}
