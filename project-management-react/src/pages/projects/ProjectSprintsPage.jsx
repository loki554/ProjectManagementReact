import { zodResolver } from '@hookform/resolvers/zod'
import { OctagonAlert } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router-dom'
import { z } from 'zod'
import { useProjectBySlug, useProjectMembers } from '../../api/projectsQueries'
import {
  useCompleteSprint,
  useCreateSprint,
  useDeleteSprint,
  useSprints,
  useStartSprint,
  useUpdateSprint,
} from '../../api/sprintsQueries'
import { useBulkUpdateTasks, useTasks } from '../../api/tasksQueries'
import { Field, inputClass, primaryButtonClass, secondaryButtonClass } from '../../components/ui/FormKit'
import { TASK_NUMBER_BADGE_CLASS, canWriteInProject, taskStatusBadgeClass } from '../../lib/constants'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { askConfirmation, confirmAction } from '../../stores/confirmStore'
import {
  daysLeft,
  formatSprintRange,
  isSprintOverdue,
  sprintOpenTaskCount,
  sprintProgressPercent,
  sprintStatusBadgeClass,
} from '../../lib/sprints'
import { SPRINT_BACKLOG, writeFilters } from '../../lib/taskFilters'
import { assigneeLabelOf } from '../../lib/taskDisplay'
import { useAuthStore } from '../../stores/authStore'
import { useToastStore } from '../../stores/toastStore'

// Столько задач страница показывает в панели спринта и в бэклоге. Это не пагинация, а
// потолок: спринт, в котором задач больше двух сотен, планированием уже не является, а
// бэклог, в котором их больше, разбирают в списке задач с его фильтрами — туда и ведёт
// ссылка «открыть в списке».
const TASKS_LIMIT = 200

function buildSprintSchema(t) {
  return z
    .object({
      name: z.string().min(1, t('auth.validation.required')).max(100),
      goal: z.string().max(2000).optional(),
      startDate: z.string().min(1, t('auth.validation.required')),
      endDate: z.string().min(1, t('auth.validation.required')),
    })
    // Та же проверка, что и на сервере (400 SPRINT_DATES_INVALID) — здесь только ради
    // того, чтобы человек увидел её рядом с полем, а не в красной строке над формой.
    .refine((values) => values.endDate >= values.startDate, {
      path: ['endDate'],
      message: t('sprints.validation.endBeforeStart'),
    })
}

/**
 * Страница спринтов проекта (4.9).
 *
 * <p>Устроена как «слева планы, справа состав»: список спринтов и панель выбранного. Так
 * видно и то, ради чего страница нужна (сколько сделано в текущем заходе и что ещё висит),
 * и то, чем её наполняют (бэклог рядом, в один клик).
 *
 * <p>Собственной ручки «задачи спринта» у страницы нет: состав приезжает обычным списком
 * задач с фильтром по спринту, а перекладывание задач между спринтом и бэклогом — это
 * массовая правка (4.6) с полем sprintId. Оба механизма уже существуют, и заводить рядом
 * третий значило бы получить второе место, где задача меняет спринт, — со своими правами,
 * своей лентой активности и своим набором ошибок.
 */
export function ProjectSprintsPage() {
  const { t, i18n } = useTranslation()
  const { projectSlug } = useParams()
  const currentUser = useAuthStore((state) => state.user)
  const pushToast = useToastStore((state) => state.pushToast)

  const { data: project } = useProjectBySlug(projectSlug)
  const projectId = project?.id
  const { data: members } = useProjectMembers(projectId)
  const { data: sprints, isLoading, isError, error } = useSprints(projectId)

  const myMembership = members?.find((member) => member.userId === currentUser?.id)
  // Планом распоряжается ADMIN и выше — как и на сервере (см. SprintService). Ниже по
  // странице MEMBER всё равно может двигать задачи: это правка задачи, а не плана.
  const canManageSprints = canWriteInProject(project, myMembership?.role, 'ADMIN')
  const canMoveTasks = canWriteInProject(project, myMembership?.role, 'MEMBER')

  const createSprint = useCreateSprint(projectId)
  const updateSprint = useUpdateSprint(projectId)
  const startSprint = useStartSprint(projectId)
  const completeSprint = useCompleteSprint(projectId)
  const deleteSprint = useDeleteSprint(projectId)
  const bulkUpdate = useBulkUpdateTasks(projectId)

  const [selectedSprintId, setSelectedSprintId] = useState(null)
  const [formMode, setFormMode] = useState(null) // null | 'create' | id правящегося спринта

  // По умолчанию открыт текущий спринт: страница отвечает на вопрос «чем мы сейчас
  // заняты», и заставлять кликать ради ответа незачем. Выбор человека при этом не
  // перебивается — этим и занята проверка на уже выбранный спринт.
  useEffect(() => {
    if (!sprints?.length) {
      return
    }
    if (!sprints.some((sprint) => sprint.id === selectedSprintId)) {
      setSelectedSprintId(sprints[0].id)
    }
  }, [sprints])

  const selectedSprint = sprints?.find((sprint) => sprint.id === selectedSprintId) ?? null
  const openSprints = useMemo(
    () => (sprints ?? []).filter((sprint) => sprint.status !== 'COMPLETED'),
    [sprints],
  )

  const schema = useMemo(() => buildSprintSchema(t), [i18n.language, t])
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm({ resolver: zodResolver(schema) })

  function openCreateForm() {
    const today = new Date().toISOString().slice(0, 10)
    reset({ name: '', goal: '', startDate: today, endDate: today })
    setFormMode('create')
  }

  function openEditForm(sprint) {
    reset({
      name: sprint.name,
      goal: sprint.goal ?? '',
      startDate: sprint.startDate,
      endDate: sprint.endDate,
    })
    setFormMode(sprint.id)
  }

  function onSubmitForm(values) {
    const payload = {
      name: values.name,
      goal: values.goal?.trim() || null,
      startDate: values.startDate,
      endDate: values.endDate,
    }
    if (formMode === 'create') {
      createSprint.mutate(payload, {
        onSuccess: (sprint) => {
          setSelectedSprintId(sprint.id)
          setFormMode(null)
        },
      })
      return
    }
    updateSprint.mutate({ sprintId: formMode, payload }, { onSuccess: () => setFormMode(null) })
  }

  /**
   * Завершение спринта — единственное действие страницы, которое спрашивает. Вопрос не
   * «точно ли», а «куда девать незакрытое»: остаться в закрытом спринте эти задачи не
   * могут, и решение за человеком. Ответ по умолчанию — бэклог, следующий спринт
   * предлагается, только если он есть.
   */
  async function onComplete(sprint) {
    const open = sprintOpenTaskCount(sprint)
    const next = openSprints.find((candidate) => candidate.id !== sprint.id)
    let moveTo = null

    if (open > 0 && next) {
      // Три ответа, а не два: раньше это спрашивал window.confirm, у которого «отмена»
      // означала не отмену, а «в бэклог», — то есть отменить завершение спринта было
      // нельзя вовсе. Теперь отказ (Esc, «Отмена») означает ровно отказ.
      const choice = await askConfirmation({
        title: t('sprints.completeConfirm'),
        body: t('sprints.completeOpenTasks', { count: open }),
        choices: [
          { id: 'backlog', label: t('sprints.completeBacklogChoice'), tone: 'primary' },
          { id: 'move', label: t('sprints.completeMoveChoice', { sprint: next.name }), tone: 'primary' },
        ],
      })
      if (!choice) {
        return
      }
      moveTo = choice === 'move' ? next.id : null
    } else if (open > 0) {
      const confirmed = await confirmAction({
        title: t('sprints.completeConfirm'),
        body: t('sprints.completeBacklogConfirm', { count: open }),
        confirmLabel: t('sprints.complete'),
        tone: 'primary',
      })
      if (!confirmed) {
        return
      }
    }

    completeSprint.mutate(
      { sprintId: sprint.id, moveUnfinishedToSprintId: moveTo },
      { onSuccess: (result) => pushToast(t('sprints.completed', { count: result.movedTasks })) },
    )
  }

  async function onDelete(sprint) {
    const confirmed = await confirmAction({
      title: t('sprints.deleteConfirm'),
      body: sprint.taskCount ? t('sprints.deleteConfirmWithTasks', { count: sprint.taskCount }) : undefined,
      confirmLabel: t('confirm.delete'),
    })
    if (!confirmed) {
      return
    }
    deleteSprint.mutate(sprint.id)
  }

  // Перекладывание задачи между бэклогом и спринтом — та же массовая правка, что в списке
  // задач, только на одну задачу: сервер ждёт список id и в этом случае.
  function moveTask(taskId, sprintId) {
    bulkUpdate.mutate(
      sprintId
        ? { taskIds: [taskId], sprintId }
        : { taskIds: [taskId], clearSprint: true },
    )
  }

  const mutationError =
    createSprint.error ??
    updateSprint.error ??
    startSprint.error ??
    completeSprint.error ??
    deleteSprint.error ??
    bulkUpdate.error

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 px-4 py-6">
      <div className="flex items-center gap-3">
        <h1 className="text-xl font-semibold text-gray-900 dark:text-gray-100">{t('sprints.title')}</h1>
        <p className="text-sm text-gray-500 dark:text-gray-400">{t('sprints.hint')}</p>
        {canManageSprints && (
          <button type="button" onClick={openCreateForm} className={`${primaryButtonClass} ml-auto shrink-0`}>
            + {t('sprints.create')}
          </button>
        )}
      </div>

      {mutationError && (
        <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(mutationError, t)}</p>
      )}

      {formMode && (
        <form
          onSubmit={handleSubmit(onSubmitForm)}
          className="grid gap-3 rounded-lg border border-gray-200 bg-white p-4 sm:grid-cols-4 dark:border-gray-700 dark:bg-gray-800"
        >
          <Field label={t('sprints.name')} error={errors.name?.message}>
            <input type="text" className={inputClass} maxLength={100} {...register('name')} />
          </Field>
          <Field label={t('sprints.startDate')} error={errors.startDate?.message}>
            <input type="date" className={inputClass} {...register('startDate')} />
          </Field>
          <Field label={t('sprints.endDate')} error={errors.endDate?.message}>
            <input type="date" className={inputClass} {...register('endDate')} />
          </Field>
          <div className="sm:col-span-4">
            <Field label={t('sprints.goal')} error={errors.goal?.message}>
              <input
                type="text"
                className={inputClass}
                maxLength={2000}
                placeholder={t('sprints.goalPlaceholder')}
                {...register('goal')}
              />
            </Field>
          </div>
          <div className="flex items-center gap-3 sm:col-span-4">
            <button
              type="submit"
              disabled={createSprint.isPending || updateSprint.isPending}
              className={primaryButtonClass}
            >
              {formMode === 'create' ? t('sprints.create') : t('sprints.save')}
            </button>
            <button type="button" onClick={() => setFormMode(null)} className={secondaryButtonClass}>
              {t('sprints.cancel')}
            </button>
            {/* Майлстоун не отдельная сущность, а спринт с одинаковыми датами — сказать об
                этом надо там, где человек эти даты вводит, иначе он будет искать
                несуществующую кнопку «создать майлстоун». */}
            <span className="text-xs text-gray-400 dark:text-gray-500">{t('sprints.milestoneHint')}</span>
          </div>
        </form>
      )}

      {isLoading && <p className="text-gray-500 dark:text-gray-400">{t('sprints.loading')}</p>}
      {isError && <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(error, t)}</p>}

      {!isLoading && !isError && (
        <div className="grid min-h-0 flex-1 gap-4 lg:grid-cols-[22rem_1fr]">
          <div className="min-h-0 space-y-2 overflow-auto">
            {sprints?.length === 0 && (
              <p className="rounded-lg border border-dashed border-gray-300 px-4 py-6 text-center text-sm text-gray-400 dark:border-gray-600 dark:text-gray-500">
                {t('sprints.empty')}
              </p>
            )}
            {sprints?.map((sprint) => (
              <SprintCard
                key={sprint.id}
                sprint={sprint}
                selected={sprint.id === selectedSprintId}
                canManage={canManageSprints}
                onSelect={() => setSelectedSprintId(sprint.id)}
                onEdit={() => openEditForm(sprint)}
                onStart={() => startSprint.mutate(sprint.id)}
                onComplete={() => onComplete(sprint)}
                onDelete={() => onDelete(sprint)}
              />
            ))}
          </div>

          <div className="grid min-h-0 gap-4 xl:grid-cols-2">
            <SprintTasksPanel
              title={selectedSprint ? selectedSprint.name : t('sprints.tasksTitle')}
              subtitle={selectedSprint?.goal}
              projectId={projectId}
              projectSlug={projectSlug}
              params={selectedSprint ? { sprintId: selectedSprint.id, size: TASKS_LIMIT } : null}
              listLink={
                selectedSprint
                  ? `/projects/${projectSlug}/tasks?${writeFilters({ sprint: selectedSprint.id, sort: 'NUMBER' })}`
                  : null
              }
              emptyText={t('sprints.sprintEmpty')}
              // Из завершённого спринта вынимать уже нечего: его состав — это история.
              actionLabel={canMoveTasks && selectedSprint?.status !== 'COMPLETED' ? t('sprints.toBacklog') : null}
              onAction={(task) => moveTask(task.id, null)}
              isPending={bulkUpdate.isPending}
            />

            <SprintTasksPanel
              title={t('sprints.backlogTitle')}
              subtitle={t('sprints.backlogHint')}
              projectId={projectId}
              projectSlug={projectSlug}
              params={{ noSprint: true, size: TASKS_LIMIT }}
              listLink={`/projects/${projectSlug}/tasks?${writeFilters({ sprint: SPRINT_BACKLOG, sort: 'NUMBER' })}`}
              emptyText={t('sprints.backlogEmpty')}
              actionLabel={
                canMoveTasks && selectedSprint && selectedSprint.status !== 'COMPLETED'
                  ? t('sprints.toSprint')
                  : null
              }
              onAction={(task) => moveTask(task.id, selectedSprint.id)}
              isPending={bulkUpdate.isPending}
            />
          </div>
        </div>
      )}
    </div>
  )
}

function SprintCard({ sprint, selected, canManage, onSelect, onEdit, onStart, onComplete, onDelete }) {
  const { t, i18n } = useTranslation()
  const percent = sprintProgressPercent(sprint)
  const overdue = isSprintOverdue(sprint)
  const left = daysLeft(sprint.endDate)

  return (
    <div
      onClick={onSelect}
      className={`cursor-pointer rounded-lg border p-3 ${
        selected
          ? 'border-purple-400 bg-purple-50 dark:border-purple-700 dark:bg-purple-950/40'
          : 'border-gray-200 bg-white hover:bg-gray-50 dark:border-gray-700 dark:bg-gray-800 dark:hover:bg-gray-700/50'
      }`}
    >
      <div className="flex items-center gap-2">
        <span className="min-w-0 flex-1 truncate font-medium text-gray-900 dark:text-gray-100">{sprint.name}</span>
        <span
          className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-medium ${sprintStatusBadgeClass(sprint.status)}`}
        >
          {t(`sprints.status.${sprint.status}`)}
        </span>
      </div>

      <p className="mt-1 flex items-center gap-1.5 text-xs text-gray-500 dark:text-gray-400">
        {formatSprintRange(sprint, i18n.language)}
        {/* Просрочен только идущий спринт: у запланированного окно ещё не наступало,
            у завершённого уже неважно (см. isSprintOverdue). */}
        {overdue && (
          <span className="flex items-center gap-1 font-medium text-red-600 dark:text-red-400">
            <OctagonAlert className="h-3.5 w-3.5" aria-hidden="true" />
            {t('sprints.overdue')}
          </span>
        )}
        {!overdue && sprint.status === 'ACTIVE' && <span>· {t('sprints.daysLeft', { count: left })}</span>}
      </p>

      <div className="mt-2 flex items-center gap-2">
        <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-gray-200 dark:bg-gray-700">
          <div className="h-full rounded-full bg-purple-500" style={{ width: `${percent}%` }} />
        </div>
        <span className="shrink-0 text-xs tabular-nums text-gray-500 dark:text-gray-400">
          {sprint.closedTaskCount}/{sprint.taskCount}
        </span>
      </div>

      {canManage && (
        // Клик по кнопкам не должен выбирать карточку заново — она и так выбрана, но
        // всплытие сюда же приводило бы к лишнему ререндеру панели задач.
        <div className="mt-2 flex items-center gap-3" onClick={(event) => event.stopPropagation()}>
          {sprint.status === 'PLANNED' && (
            <button type="button" onClick={onStart} className="text-xs font-medium text-green-700 hover:underline dark:text-green-400">
              {t('sprints.start')}
            </button>
          )}
          {sprint.status === 'ACTIVE' && (
            <button type="button" onClick={onComplete} className="text-xs font-medium text-purple-700 hover:underline dark:text-purple-400">
              {t('sprints.complete')}
            </button>
          )}
          <button type="button" onClick={onEdit} className="text-xs text-purple-600 hover:underline dark:text-purple-400">
            {t('sprints.edit')}
          </button>
          <button type="button" onClick={onDelete} className="text-xs text-red-600 hover:underline dark:text-red-400">
            {t('sprints.delete')}
          </button>
        </div>
      )}
    </div>
  )
}

/**
 * Список задач одной колонки — состава спринта или бэклога. Обе колонки читают один и тот
 * же список задач проекта, отличаясь только фильтром: спринт это {@code sprintId}, бэклог —
 * {@code noSprint}.
 */
function SprintTasksPanel({
  title,
  subtitle,
  projectId,
  projectSlug,
  params,
  listLink,
  emptyText,
  actionLabel,
  onAction,
  isPending,
}) {
  const { t } = useTranslation()
  // Пока спринт не выбран, панели нечего показывать — и запрашивать тоже: projectId,
  // отданный как undefined, гасит запрос (см. enabled в useTasks), а не грузит первую
  // страницу всех задач проекта в фон.
  const { data, isLoading } = useTasks(params ? projectId : undefined, params ?? {})
  const tasks = params ? (data?.items ?? []) : []

  return (
    <div className="flex min-h-0 flex-col rounded-lg border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
      <div className="flex items-baseline gap-2 border-b border-gray-200 px-4 py-3 dark:border-gray-700">
        <h2 className="min-w-0 truncate text-sm font-semibold text-gray-900 dark:text-gray-100">{title}</h2>
        {listLink && (
          <Link to={listLink} className="ml-auto shrink-0 text-xs text-purple-600 hover:underline dark:text-purple-400">
            {t('sprints.openInList')}
          </Link>
        )}
      </div>
      {subtitle && (
        <p className="border-b border-gray-100 px-4 py-2 text-xs text-gray-500 dark:border-gray-700/60 dark:text-gray-400">
          {subtitle}
        </p>
      )}

      <ul className="min-h-0 flex-1 divide-y divide-gray-100 overflow-auto dark:divide-gray-700/60">
        {isLoading && (
          <li className="px-4 py-3 text-sm text-gray-500 dark:text-gray-400">{t('tasks.loading')}</li>
        )}
        {!isLoading && tasks.length === 0 && (
          <li className="px-4 py-3 text-sm text-gray-400 dark:text-gray-500">{emptyText}</li>
        )}
        {tasks.map((task) => (
          <li key={task.id} className="flex items-center gap-2 px-4 py-2">
            <Link
              to={`/projects/${projectSlug}/tasks/${task.taskNumber}`}
              className="flex min-w-0 flex-1 items-center gap-2"
            >
              <span className={TASK_NUMBER_BADGE_CLASS}>#{task.taskNumber}</span>
              <span className="min-w-0 flex-1 truncate text-sm text-gray-900 hover:underline dark:text-gray-100">
                {task.title}
              </span>
              <span
                className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-medium ${taskStatusBadgeClass(task.status)}`}
              >
                {t(`tasks.status.${task.status}`)}
              </span>
              {task.assignee && (
                <span className="hidden shrink-0 text-xs text-gray-500 xl:inline dark:text-gray-400">
                  {assigneeLabelOf(task)}
                </span>
              )}
            </Link>
            {actionLabel && (
              <button
                type="button"
                onClick={() => onAction(task)}
                disabled={isPending}
                className="shrink-0 text-xs text-purple-600 hover:underline disabled:opacity-60 dark:text-purple-400"
              >
                {actionLabel}
              </button>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}
