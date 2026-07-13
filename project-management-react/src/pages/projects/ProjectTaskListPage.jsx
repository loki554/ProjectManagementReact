import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useProjectBySlug, useProjectMembers } from '../../api/projectsQueries'
import { useTags } from '../../api/tagsQueries'
import { useTasks } from '../../api/tasksQueries'
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
import { useAuthStore } from '../../stores/authStore'

const UNASSIGNED = '__unassigned__'

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
  hours: (a, b) => Number(a.totalHoursSpent) - Number(b.totalHoursSpent),
}

function assigneeLabelOf(task) {
  return task.assignee ? `${task.assignee.lastName} ${task.assignee.firstName}` : ''
}

function SortableHeader({ colKey, sort, onSort, children, className = '' }) {
  const active = sort.key === colKey
  return (
    <th className={`px-3 py-2 ${className}`}>
      <button
        type="button"
        onClick={() => onSort(colKey)}
        className={`flex items-center gap-1 text-left text-xs font-semibold uppercase tracking-wide ${
          active ? 'text-purple-700 dark:text-purple-400' : 'text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200'
        }`}
      >
        {children}
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

  const myMembership = members?.find((member) => member.userId === currentUser?.id)
  const canManage = myMembership ? roleIsAtLeast(myMembership.role, 'MEMBER') : false

  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [assigneeFilter, setAssigneeFilter] = useState('')
  const [tagFilter, setTagFilter] = useState('')
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
      return true
    })
    return filtered.sort((a, b) => COMPARATORS[sort.key](a, b) * sort.dir)
  }, [tasks, search, statusFilter, assigneeFilter, tagFilter, sort])

  return (
    <div className="mx-auto max-w-7xl px-4 py-8">
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <input
          type="search"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          placeholder={t('taskList.searchPlaceholder')}
          className={`${inputClass} max-w-64`}
        />
        <select
          value={statusFilter}
          onChange={(event) => setStatusFilter(event.target.value)}
          className={`${inputClass} max-w-56`}
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
          className={`${inputClass} max-w-56`}
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
          className={`${inputClass} max-w-56`}
        >
          <option value="">{t('taskList.allTags')}</option>
          {tags?.map((tag) => (
            <option key={tag.id} value={tag.id}>
              {tag.name}
            </option>
          ))}
        </select>
        {canManage && (
          <Link
            to={`/projects/${projectSlug}/tasks/new`}
            className={`${primaryButtonClass} ml-auto whitespace-nowrap`}
          >
            + {t('taskList.newTask')}
          </Link>
        )}
      </div>

      {isLoading && <p className="text-gray-500 dark:text-gray-400">{t('tasks.loading')}</p>}
      {isError && <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(error, t)}</p>}

      {!isLoading && !isError && (
        <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
          <table className="w-full min-w-200 text-sm">
            <thead className="border-b border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-900/40">
              <tr>
                <SortableHeader colKey="number" sort={sort} onSort={toggleSort} className="w-16">
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
                <SortableHeader colKey="hours" sort={sort} onSort={toggleSort}>
                  {t('tasks.timeLogs.hoursLabel')}
                </SortableHeader>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 dark:divide-gray-700">
              {visibleTasks.length === 0 && (
                <tr>
                  <td colSpan={8} className="px-3 py-6 text-center text-sm text-gray-400 dark:text-gray-500">
                    {t('taskList.empty')}
                  </td>
                </tr>
              )}
              {visibleTasks.map((task) => (
                <tr
                  key={task.id}
                  onClick={() => navigate(`/projects/${projectSlug}/tasks/${task.taskNumber}`)}
                  className="cursor-pointer hover:bg-purple-50 dark:hover:bg-purple-950/30"
                >
                  <td className="px-3 py-2">
                    <span className={TASK_NUMBER_BADGE_CLASS}>#{task.taskNumber}</span>
                  </td>
                  <td className="max-w-90 truncate px-3 py-2 font-medium text-gray-900 dark:text-gray-100">
                    {task.title}
                  </td>
                  <td className="px-3 py-2">
                    <span
                      className={`rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap ${taskStatusBadgeClass(task.status)}`}
                    >
                      {t(`tasks.status.${task.status}`)}
                    </span>
                  </td>
                  <td className="px-3 py-2 text-gray-600 dark:text-gray-400">
                    {task.assignee ? assigneeLabelOf(task) : (
                      <span className="text-gray-400 dark:text-gray-500">{t('tasks.unassigned')}</span>
                    )}
                  </td>
                  <td className="px-3 py-2">
                    <span
                      className={`rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap ${taskUrgencyBadgeClass(task.urgency)}`}
                    >
                      {t(`urgency.${task.urgency}`)}
                    </span>
                  </td>
                  <td className="px-3 py-2 whitespace-nowrap text-gray-600 dark:text-gray-400">
                    {task.dueDate
                      ? new Date(task.dueDate).toLocaleString(i18n.language, {
                          dateStyle: 'short',
                          timeStyle: 'short',
                        })
                      : ''}
                  </td>
                  <td className="px-3 py-2">
                    {task.tag && (
                      <span
                        className="rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap"
                        style={tagBadgeStyle(task.tag.color)}
                      >
                        {task.tag.name}
                      </span>
                    )}
                  </td>
                  <td className="px-3 py-2 text-gray-600 dark:text-gray-400">
                    {Number(task.totalHoursSpent).toFixed(2)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
