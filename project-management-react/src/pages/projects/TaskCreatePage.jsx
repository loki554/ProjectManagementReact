import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useMemo, useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link, Navigate, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { z } from 'zod'
import { useProjectBySlug, useProjectMembers } from '../../api/projectsQueries'
import { useCategories } from '../../api/categoriesQueries'
import { useSprints } from '../../api/sprintsQueries'
import { useTags } from '../../api/tagsQueries'
import { useCreateSubtask, useCreateTask, useTaskByNumber } from '../../api/tasksQueries'
import { useTaskTemplate, useTaskTemplates } from '../../api/taskTemplatesQueries'
import { MarkdownEditor } from '../../components/markdown/MarkdownEditor'
import { Combobox } from '../../components/ui/Combobox'
import { Field, inputClass, primaryButtonClass, secondaryButtonClass } from '../../components/ui/FormKit'
import {
  TASK_NUMBER_BADGE_CLASS,
  TASK_STATUSES,
  TASK_URGENCIES,
  canWriteInProject,
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
    category: z.string().max(100).optional(),
    sprintId: z.string().optional(),
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
  const { data: categories } = useCategories(projectId)
  const { data: sprints } = useSprints(projectId)
  const { data: templates } = useTaskTemplates(projectId)

  // Выбранный шаблон (4.13). Список шаблонов приходит без пунктов чек-листа — за полным
  // шаблоном ходим только тогда, когда его выбрали.
  const [templateId, setTemplateId] = useState('')
  const { data: selectedTemplate } = useTaskTemplate(templateId || null)
  // Combobox оперирует именами: в задаче категория задаётся свободным вводом, и бэкенд
  // сам сопоставляет имя со справочником (создавая недостающую запись).
  const categoryNames = useMemo(() => (categories ?? []).map((category) => category.name), [categories])
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
  const canManage = canWriteInProject(project, myMembership?.role, 'MEMBER')

  const listPath = `/projects/${projectSlug}/tasks`
  const backPath = parentNumber ? `/projects/${projectSlug}/tasks/${parentNumber}` : listPath

  const schema = useMemo(() => buildTaskSchema(t), [i18n.language, t])
  const {
    register,
    control,
    handleSubmit,
    setValue,
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
      category: '',
      sprintId: '',
    },
  })

  /**
   * Шаблон заполняет форму, а не создаёт задачу за человека (4.13). Подставляются только
   * те поля, про которые шаблон что-то говорит: null в шаблоне — это «ничего не говорю»,
   * и затирать им уже введённое было бы хуже, чем не подставить ничего. Всё подставленное
   * остаётся обычным содержимым формы: его видно и его правят до нажатия «создать».
   *
   * Чек-лист шаблона сюда не попадает — в форме заведения задачи его попросту нет. Его
   * копирует сервер по templateId, который уезжает вместе с формой (см.
   * TaskService.applyTemplateChecklist); ниже показано, сколько пунктов приедет.
   */
  useEffect(() => {
    if (!selectedTemplate || selectedTemplate.id !== templateId) {
      return
    }
    if (selectedTemplate.title) {
      setValue('title', selectedTemplate.title, { shouldDirty: true })
    }
    if (selectedTemplate.description) {
      setValue('description', selectedTemplate.description, { shouldDirty: true })
    }
    if (selectedTemplate.urgency) {
      setValue('urgency', selectedTemplate.urgency, { shouldDirty: true })
    }
    if (selectedTemplate.tag) {
      setValue('tagId', selectedTemplate.tag.id, { shouldDirty: true })
    }
    if (selectedTemplate.category) {
      setValue('category', selectedTemplate.category.name, { shouldDirty: true })
    }
  }, [selectedTemplate, templateId, setValue])

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
        category: values.category?.trim() || null,
        sprintId: values.sprintId || null,
        // Шаблон уезжает вместе с формой ради одного — чек-листа: остальные его поля уже
        // лежат в values, подставленные выше и, возможно, поправленные руками.
        templateId: templateId || null,
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
      <Link to={backPath} className="text-sm text-purple-600 hover:underline dark:text-purple-400">
        {parentNumber ? t('tasks.detail.backToParent') : t('tasks.create.backToList')}
      </Link>

      {isLoading && <p className="mt-4 text-gray-500 dark:text-gray-400">{t('app.loading')}</p>}
      {parentIsError && (
        <p className="mt-4 text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(parentError, t)}</p>
      )}

      {!isLoading && !parentIsError && (
        <div className="mt-4 rounded-lg border border-gray-200 bg-white p-6 dark:border-gray-700 dark:bg-gray-800">
          <h1 className="text-lg font-semibold text-gray-900 dark:text-gray-100">
            {parentNumber ? t('tasks.create.subtaskTitle') : t('tasks.create.title')}
          </h1>

          {parentTask && (
            <p className="mt-2 text-sm text-gray-500 dark:text-gray-400">
              {t('tasks.create.parentLabel')}:{' '}
              <Link to={backPath} className="text-purple-700 hover:underline dark:text-purple-400">
                <span className={TASK_NUMBER_BADGE_CLASS}>#{parentTask.taskNumber}</span>{' '}
                {parentTask.title}
              </Link>
            </p>
          )}

          <form onSubmit={handleSubmit(onCreate)} className="mt-4 space-y-4">
            {/* Шаблон (4.13) стоит первым полем и отделён чертой: это не свойство задачи, а
                способ заполнить форму, и выбирают его до того, как начали печатать. Если
                шаблонов в проекте нет, поля нет вовсе — пустой селект на каждой форме
                заведения задачи был бы напоминанием о возможности, которой не пользуются. */}
            {templates && templates.length > 0 && (
              <div className="border-b border-gray-200 pb-4 dark:border-gray-700">
                <Field label={t('taskTemplates.pickLabel')}>
                  <select
                    className={inputClass}
                    value={templateId}
                    onChange={(event) => setTemplateId(event.target.value)}
                  >
                    <option value="">{t('taskTemplates.noTemplate')}</option>
                    {templates.map((template) => (
                      <option key={template.id} value={template.id}>
                        {template.name}
                      </option>
                    ))}
                  </select>
                </Field>
                {selectedTemplate?.id === templateId && selectedTemplate.itemCount > 0 && (
                  <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
                    {t('taskTemplates.checklistWillBeAdded', { count: selectedTemplate.itemCount })}
                  </p>
                )}
              </div>
            )}

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

            {/* Спринт (4.9) — обычное поле задачи, как тэг. Завершённых спринтов здесь
                нет: в закрытый заход новую задачу не планируют, и сервер такой запрос
                всё равно отклонит (409 SPRINT_COMPLETED). */}
            <Field label={t('tasks.detail.sprintLabel')}>
              <select className={inputClass} {...register('sprintId')}>
                <option value="">{t('tasks.noSprint')}</option>
                {sprints?.filter((sprint) => sprint.status !== 'COMPLETED').map((sprint) => (
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

            {activeMutation.isError && (
              <p className="text-sm text-red-600 dark:text-red-400">
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
