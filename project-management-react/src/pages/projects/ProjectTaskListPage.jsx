import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useProjectBySlug, useProjectMembers } from '../../api/projectsQueries'
import { useCategories } from '../../api/categoriesQueries'
import { useTags } from '../../api/tagsQueries'
import { useTasks } from '../../api/tasksQueries'
import { UserAvatar } from '../../components/ui/UserAvatar'
import { inputClass, primaryButtonClass } from '../../components/ui/FormKit'
import {
  TASK_NUMBER_BADGE_CLASS,
  TASK_STATUSES,
  TASK_URGENCIES,
  roleIsAtLeast,
  taskStatusBadgeClass,
  taskUrgencyBadgeClass,
} from '../../lib/constants'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { tagBadgeStyle } from '../../lib/tagColor'
import { assigneeLabelOf, formatDueDate, formatHours, isTaskOverdue } from '../../lib/taskDisplay'
import { useAuthStore } from '../../stores/authStore'

const UNASSIGNED = '__unassigned__'
// "Без категории" в фильтре — свой сентинел, который заведомо не совпадёт с реальным
// id категории (ср. UNASSIGNED).
const NO_CATEGORY = '__no_category__'

// Компараторы по ключу колонки. Для статуса/срочности порядок — как в канбане/селектах
// (индекс в фиксированном массиве), а не алфавит локализованных подписей.
const COMPARATORS = {
  number: (a, b) => a.taskNumber - b.taskNumber,
  title: (a, b) => a.title.localeCompare(b.title),
  status: (a, b) => TASK_STATUSES.indexOf(a.status) - TASK_STATUSES.indexOf(b.status),
  assignee: (a, b) => assigneeLabelOf(a).localeCompare(assigneeLabelOf(b)),
  urgency: (a, b) => TASK_URGENCIES.indexOf(a.urgency) - TASK_URGENCIES.indexOf(b.urgency),
  // Задачи без срока — в конец при любом направлении сортировки.
  dueDate: (a, b) => {
    if (!a.dueDate && !b.dueDate) return 0
    if (!a.dueDate) return 1
    if (!b.dueDate) return -1
    return new Date(a.dueDate) - new Date(b.dueDate)
  },
  tag: (a, b) => (a.tag?.name ?? '').localeCompare(b.tag?.name ?? ''),
  // Задачи без категории — в конец при любом направлении (как dueDate): пустая строка
  // иначе всплывала бы наверх и прятала заполненные значения.
  category: (a, b) => {
    if (!a.category && !b.category) return 0
    if (!a.category) return 1
    if (!b.category) return -1
    return a.category.name.localeCompare(b.category.name)
  },
  hours: (a, b) => Number(a.totalHoursSpent) - Number(b.totalHoursSpent),
}

const cellClass = 'px-3 py-2 align-middle'

function SortableHeader({ colKey, sort, onSort, children, align = 'left' }) {
  const active = sort.key === colKey
  return (
    // Фон и нижняя граница — на самой ячейке, а не на <thead>/<tr>: у залипающей
    // шапки таблицы браузеры не всегда отрисовывают фон и border строки.
    <th
      scope="col"
      className="border-b border-gray-200 bg-gray-50 px-3 py-2 dark:border-gray-700 dark:bg-gray-900"
    >
      <button
        type="button"
        onClick={() => onSort(colKey)}
        className={`flex w-full items-center gap-1 text-xs font-semibold tracking-wide uppercase ${
          align === 'right' ? 'justify-end' : 'text-left'
        } ${
          active
            ? 'text-purple-700 dark:text-purple-400'
            : 'text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200'
        }`}
      >
        <span className="truncate">{children}</span>
        <span aria-hidden="true" className={active ? '' : 'invisible'}>
          {sort.dir === 1 ? '▲' : '▼'}
        </span>
      </button>
    </th>
  )
}

export function ProjectTaskListPage() {
  const { t, i18n } = useTranslation()
  const { projectSlug } = useParams()
  const navigate = useNavigate()
  const currentUser = useAuthStore((state) => state.user)

  const { data: project } = useProjectBySlug(projectSlug)
  const projectId = project?.id
  const { data: tasks, isLoading, isError, error } = useTasks(projectId)
  const { data: members } = useProjectMembers(projectId)
  const { data: tags } = useTags(projectId)
  const { data: categories } = useCategories(projectId)

  const myMembership = members?.find((member) => member.userId === currentUser?.id)
  const canManage = myMembership ? roleIsAtLeast(myMembership.role, 'MEMBER') : false

  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [assigneeFilter, setAssigneeFilter] = useState('')
  const [tagFilter, setTagFilter] = useState('')
  const [categoryFilter, setCategoryFilter] = useState('')
  const [sort, setSort] = useState({ key: 'number', dir: 1 })

  function toggleSort(key) {
    setSort((prev) => (prev.key === key ? { key, dir: -prev.dir } : { key, dir: 1 }))
  }

  const visibleTasks = useMemo(() => {
    const query = search.trim().toLowerCase()
    const filtered = (tasks ?? []).filter((task) => {
      if (query && !task.title.toLowerCase().includes(query)) return false
      if (statusFilter && task.status !== statusFilter) return false
      if (assigneeFilter === UNASSIGNED) {
        if (task.assignee) return false
      } else if (assigneeFilter && task.assignee?.id !== assigneeFilter) {
        return false
      }
      if (tagFilter && task.tag?.id !== tagFilter) return false
      if (categoryFilter === NO_CATEGORY) {
        if (task.category) return false
      } else if (categoryFilter && task.category?.id !== categoryFilter) {
        return false
      }
      return true
    })
    return filtered.sort((a, b) => COMPARATORS[sort.key](a, b) * sort.dir)
  }, [tasks, search, statusFilter, assigneeFilter, tagFilter, categoryFilter, sort])

  return (
    <div className="flex h-full flex-col gap-3 px-4 py-4">
      <div className="flex flex-wrap items-center gap-2">
        <input
          type="search"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          placeholder={t('taskList.searchPlaceholder')}
          className={`${inputClass} w-64`}
        />
        <select
          value={statusFilter}
          onChange={(event) => setStatusFilter(event.target.value)}
          className={`${inputClass} w-44`}
        >
          <option value="">{t('taskList.allStatuses')}</option>
          {TASK_STATUSES.map((status) => (
            <option key={status} value={status}>
              {t(`tasks.status.${status}`)}
            </option>
          ))}
        </select>
        <select
          value={assigneeFilter}
          onChange={(event) => setAssigneeFilter(event.target.value)}
          className={`${inputClass} w-52`}
        >
          <option value="">{t('taskList.allAssignees')}</option>
          <option value={UNASSIGNED}>{t('tasks.unassigned')}</option>
          {members?.map((member) => (
            <option key={member.userId} value={member.userId}>
              {member.lastName} {member.firstName}
            </option>
          ))}
        </select>
        <select
          value={tagFilter}
          onChange={(event) => setTagFilter(event.target.value)}
          className={`${inputClass} w-44`}
        >
          <option value="">{t('taskList.allTags')}</option>
          {tags?.map((tag) => (
            <option key={tag.id} value={tag.id}>
              {tag.name}
            </option>
          ))}
        </select>
        <select
          value={categoryFilter}
          onChange={(event) => setCategoryFilter(event.target.value)}
          className={`${inputClass} w-52`}
        >
          <option value="">{t('taskList.allCategories')}</option>
          <option value={NO_CATEGORY}>{t('tasks.noCategory')}</option>
          {categories?.map((category) => (
            <option key={category.id} value={category.id}>
              {category.name}
            </option>
          ))}
        </select>
        <span className="ml-auto text-sm whitespace-nowrap text-gray-500 dark:text-gray-400">
          {t('taskList.counter', { shown: visibleTasks.length, total: tasks?.length ?? 0 })}
        </span>
        {canManage && (
          <Link to={`/projects/${projectSlug}/tasks/new`} className={`${primaryButtonClass} whitespace-nowrap`}>
            + {t('taskList.newTask')}
          </Link>
        )}
      </div>

      {isLoading && <p className="text-gray-500 dark:text-gray-400">{t('tasks.loading')}</p>}
      {isError && <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(error, t)}</p>}

      {!isLoading && !isError && (
        // Скроллится сама таблица, а не страница: шапка с сортировкой залипает сверху
        // и остаётся видимой на любом количестве задач.
        <div className="min-h-0 flex-1 overflow-auto rounded-lg border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
          {/* table-fixed + colgroup: все колонки, кроме названия, имеют предсказуемую
              ширину, а остаток отдаётся названию — на широком экране оно почти никогда
              не обрезается. */}
          <table className="w-full min-w-260 table-fixed text-sm">
            <colgroup>
              <col className="w-14" />
              <col />
              <col className="w-32" />
              <col className="w-52" />
              <col className="w-28" />
              <col className="w-40" />
              <col className="w-32" />
              <col className="w-20" />
            </colgroup>
            <thead className="sticky top-0 z-10">
              <tr>
                <SortableHeader colKey="number" sort={sort} onSort={toggleSort}>
                  №
                </SortableHeader>
                <SortableHeader colKey="title" sort={sort} onSort={toggleSort}>
                  {t('tasks.detail.titleLabel')}
                </SortableHeader>
                <SortableHeader colKey="status" sort={sort} onSort={toggleSort}>
                  {t('tasks.detail.statusLabel')}
                </SortableHeader>
                <SortableHeader colKey="assignee" sort={sort} onSort={toggleSort}>
                  {t('tasks.detail.assigneeLabel')}
                </SortableHeader>
                <SortableHeader colKey="urgency" sort={sort} onSort={toggleSort}>
                  {t('tasks.detail.urgencyLabel')}
                </SortableHeader>
                <SortableHeader colKey="dueDate" sort={sort} onSort={toggleSort}>
                  {t('tasks.detail.dueDateLabel')}
                </SortableHeader>
                <SortableHeader colKey="tag" sort={sort} onSort={toggleSort}>
                  {t('tasks.detail.tagLabel')}
                </SortableHeader>
                <SortableHeader colKey="category" sort={sort} onSort={toggleSort}>
                  {t('tasks.detail.categoryLabel')}
                </SortableHeader>
                <SortableHeader colKey="hours" sort={sort} onSort={toggleSort} align="right">
                  {t('tasks.timeLogs.hoursLabel')}
                </SortableHeader>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 dark:divide-gray-700/60">
              {visibleTasks.length === 0 && (
                <tr>
                  <td colSpan={9} className="px-3 py-6 text-center text-sm text-gray-400 dark:text-gray-500">
                    {t('taskList.empty')}
                  </td>
                </tr>
              )}
              {visibleTasks.map((task) => {
                const overdue = isTaskOverdue(task)
                const hours = formatHours(task.totalHoursSpent)
                return (
                  <tr
                    key={task.id}
                    onClick={() => navigate(`/projects/${projectSlug}/tasks/${task.taskNumber}`)}
                    // Чередование фона строк — на широкой таблице глаз не теряет строку
                    // между колонкой «Название» и колонкой «Часы».
                    className="cursor-pointer even:bg-gray-50/70 hover:bg-purple-50 dark:even:bg-gray-900/30 dark:hover:bg-purple-950/30"
                  >
                    <td className={cellClass}>
                      <span className={TASK_NUMBER_BADGE_CLASS}>#{task.taskNumber}</span>
                    </td>
                    <td className={cellClass}>
                      <span
                        title={task.title}
                        className="line-clamp-2 font-medium text-gray-900 dark:text-gray-100"
                      >
                        {task.title}
                      </span>
                    </td>
                    <td className={cellClass}>
                      <span
                        className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap ${taskStatusBadgeClass(task.status)}`}
                      >
                        {t(`tasks.status.${task.status}`)}
                      </span>
                    </td>
                    <td className={cellClass}>
                      {task.assignee ? (
                        <span className="flex items-center gap-2 text-gray-700 dark:text-gray-300">
                          <UserAvatar user={task.assignee} sizeClass="h-6 w-6" />
                          <span className="truncate">{assigneeLabelOf(task)}</span>
                        </span>
                      ) : (
                        <span className="text-gray-400 dark:text-gray-500">{t('tasks.unassigned')}</span>
                      )}
                    </td>
                    <td className={cellClass}>
                      <span
                        className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap ${taskUrgencyBadgeClass(task.urgency)}`}
                      >
                        {t(`urgency.${task.urgency}`)}
                      </span>
                    </td>
                    <td className={cellClass}>
                      {task.dueDate ? (
                        <span
                          title={overdue ? t('tasks.overdue') : undefined}
                          className={`whitespace-nowrap ${
                            overdue
                              ? 'font-medium text-red-600 dark:text-red-400'
                              : 'text-gray-600 dark:text-gray-400'
                          }`}
                        >
                          {formatDueDate(task.dueDate, i18n.language)}
                        </span>
                      ) : (
                        <span className="text-gray-300 dark:text-gray-600">—</span>
                      )}
                    </td>
                    <td className={cellClass}>
                      {task.tag ? (
                        <span
                          className="inline-block max-w-full truncate rounded-full px-2 py-0.5 text-xs font-medium"
                          style={tagBadgeStyle(task.tag.color)}
                        >
                          {task.tag.name}
                        </span>
                      ) : (
                        <span className="text-gray-300 dark:text-gray-600">—</span>
                      )}
                    </td>
                    <td className={cellClass}>
                      {task.category ? (
                        <span className="block max-w-[12rem] truncate text-gray-600 dark:text-gray-400">
                          {task.category.name}
                        </span>
                      ) : (
                        <span className="text-gray-300 dark:text-gray-600">—</span>
                      )}
                    </td>
                    <td className={`${cellClass} text-right tabular-nums`}>
                      {hours ? (
                        <span className="text-gray-700 dark:text-gray-300">{hours}</span>
                      ) : (
                        <span className="text-gray-300 dark:text-gray-600">—</span>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
