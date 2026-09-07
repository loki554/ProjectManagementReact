import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useProjectBySlug, useProjectMembers } from '../../api/projectsQueries'
import { useCategories } from '../../api/categoriesQueries'
import { useTags } from '../../api/tagsQueries'
import { useBulkUpdateTasks, useTasks } from '../../api/tasksQueries'
import { BulkActionsBar } from '../../components/tasks/BulkActionsBar'
import { Pagination } from '../../components/ui/Pagination'
import { UserAvatar } from '../../components/ui/UserAvatar'
import { inputClass, primaryButtonClass } from '../../components/ui/FormKit'
import {
  TASK_NUMBER_BADGE_CLASS,
  TASK_STATUSES,
  roleIsAtLeast,
  taskStatusBadgeClass,
  taskUrgencyBadgeClass,
} from '../../lib/constants'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { tagBadgeStyle } from '../../lib/tagColor'
import { assigneeLabelOf, formatDueDate, formatHours, isTaskOverdue } from '../../lib/taskDisplay'
import { useDebouncedValue } from '../../lib/useDebouncedValue'
import { useAuthStore } from '../../stores/authStore'
import { useToastStore } from '../../stores/toastStore'

const UNASSIGNED = '__unassigned__'
// "Без категории" в фильтре — свой сентинел, который заведомо не совпадёт с реальным
// id категории (ср. UNASSIGNED).
const NO_CATEGORY = '__no_category__'

const PAGE_SIZE = 50

// Ключи сортировки — это значения TaskSortKey на бэкенде: и порядок, и фильтрация теперь
// считаются в БД (3.3). Клиентские компараторы, стоявшие здесь раньше, работали по
// загруженному массиву и с постраничной выдачей давали бы отсортированную страницу
// вместо первой страницы отсортированного списка.
const SORT = {
  NUMBER: 'NUMBER',
  TITLE: 'TITLE',
  STATUS: 'STATUS',
  ASSIGNEE: 'ASSIGNEE',
  URGENCY: 'URGENCY',
  DUE_DATE: 'DUE_DATE',
  TAG: 'TAG',
  CATEGORY: 'CATEGORY',
  HOURS: 'HOURS',
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
  const [sort, setSort] = useState({ key: SORT.NUMBER, dir: 1 })
  const [page, setPage] = useState(0)

  // Ввод в поиске уходит на сервер, поэтому не на каждый символ.
  const debouncedSearch = useDebouncedValue(search.trim())

  // Любая смена фильтра или порядка меняет и состав списка: остаться на седьмой странице
  // выдачи, в которой теперь две, значит увидеть пустую таблицу вместо результата.
  useEffect(() => {
    setPage(0)
  }, [debouncedSearch, statusFilter, assigneeFilter, tagFilter, categoryFilter, sort])

  const params = useMemo(() => {
    const query = { sort: sort.key, descending: sort.dir === -1, page, size: PAGE_SIZE }
    if (debouncedSearch) query.search = debouncedSearch
    if (statusFilter) query.status = statusFilter
    // "Без исполнителя"/"без категории" — отдельные флаги, а не значение id: пустой id на
    // сервере означает "фильтр не задан", и выразить им "поле пустое" нечем.
    if (assigneeFilter === UNASSIGNED) query.unassigned = true
    else if (assigneeFilter) query.assigneeId = assigneeFilter
    if (tagFilter) query.tagId = tagFilter
    if (categoryFilter === NO_CATEGORY) query.uncategorized = true
    else if (categoryFilter) query.categoryId = categoryFilter
    return query
  }, [debouncedSearch, statusFilter, assigneeFilter, tagFilter, categoryFilter, sort, page])

  const { data, isLoading, isError, error } = useTasks(projectId, params)
  const visibleTasks = data?.items ?? []

  // Массовые операции (4.6). Выделение живёт ровно столько, сколько на экране та же
  // страница тех же задач: смена фильтра, сортировки или номера страницы меняет params,
  // а вместе с ними и состав таблицы. Переносить выделение через это — значит однажды
  // применить правку к задачам, которых человек уже не видит.
  const [selectedIds, setSelectedIds] = useState(() => new Set())
  const lastToggledIndex = useRef(null)
  const bulkUpdate = useBulkUpdateTasks(projectId)
  const pushToast = useToastStore((state) => state.pushToast)

  useEffect(() => {
    setSelectedIds(new Set())
    lastToggledIndex.current = null
  }, [params])

  const allVisibleSelected =
    visibleTasks.length > 0 && visibleTasks.every((task) => selectedIds.has(task.id))
  const someVisibleSelected = visibleTasks.some((task) => selectedIds.has(task.id))

  function toggleAllVisible() {
    setSelectedIds(allVisibleSelected ? new Set() : new Set(visibleTasks.map((task) => task.id)))
    lastToggledIndex.current = null
  }

  // Shift+клик выделяет диапазон от предыдущей отмеченной строки — на пятидесяти задачах
  // это разница между двумя кликами и пятьюдесятью, ради которой пункт и заведён.
  // Обработчик висит на click, а не на change: shiftKey есть только у события мыши.
  function toggleTask(index, event) {
    const anchor =
      event.shiftKey && lastToggledIndex.current !== null ? lastToggledIndex.current : index
    const [from, to] = anchor <= index ? [anchor, index] : [index, anchor]
    const select = !selectedIds.has(visibleTasks[index].id)
    setSelectedIds((prev) => {
      const next = new Set(prev)
      for (let i = from; i <= to; i++) {
        if (select) {
          next.add(visibleTasks[i].id)
        } else {
          next.delete(visibleTasks[i].id)
        }
      }
      return next
    })
    lastToggledIndex.current = index
  }

  function applyBulk(payload) {
    bulkUpdate.mutate(
      { ...payload, taskIds: [...selectedIds] },
      {
        onSuccess: (result) => {
          pushToast(t('taskList.bulk.applied', { count: result.updated }))
          setSelectedIds(new Set())
        },
      },
    )
  }

  function toggleSort(key) {
    setSort((prev) => (prev.key === key ? { key, dir: -prev.dir } : { key, dir: 1 }))
  }

  return (
    <div className="flex h-full flex-col gap-3 px-4 py-4">
      <div className="flex items-center gap-3">
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
        {canManage && (
          <Link to={`/projects/${projectSlug}/tasks/new`} className={`${primaryButtonClass} whitespace-nowrap`}>
            + {t('taskList.newTask')}
          </Link>
        )}
        <span className="ml-auto text-sm whitespace-nowrap text-gray-500 dark:text-gray-400">
          {t('taskList.total', { count: data?.totalItems ?? 0 })}
        </span>
      </div>

      {canManage && selectedIds.size > 0 && (
        <BulkActionsBar
          selectedCount={selectedIds.size}
          members={members}
          tags={tags}
          onApply={applyBulk}
          onCancel={() => setSelectedIds(new Set())}
          isPending={bulkUpdate.isPending}
          error={bulkUpdate.error}
        />
      )}

      {/* isLoading, а не isFetching: с keepPreviousData таблица остаётся на экране, пока
          грузится следующая страница, и подменять её на «Загрузка...» на каждый шаг
          пагинации значило бы вернуть то самое мигание, ради которого она включена. */}
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
              {canManage && <col className="w-10" />}
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
                {canManage && (
                  <th
                    scope="col"
                    className="border-b border-gray-200 bg-gray-50 px-3 py-2 dark:border-gray-700 dark:bg-gray-900"
                  >
                    {/* indeterminate («выделена часть строк») нельзя выставить разметкой —
                        это свойство DOM-узла, а не атрибут, поэтому ref. */}
                    <input
                      type="checkbox"
                      aria-label={t('taskList.bulk.selectAll')}
                      checked={allVisibleSelected}
                      readOnly
                      ref={(node) => {
                        if (node) {
                          node.indeterminate = someVisibleSelected && !allVisibleSelected
                        }
                      }}
                      onClick={toggleAllVisible}
                      className="h-4 w-4 accent-purple-600"
                    />
                  </th>
                )}
                <SortableHeader colKey={SORT.NUMBER} sort={sort} onSort={toggleSort}>
                  №
                </SortableHeader>
                <SortableHeader colKey={SORT.TITLE} sort={sort} onSort={toggleSort}>
                  {t('tasks.detail.titleLabel')}
                </SortableHeader>
                <SortableHeader colKey={SORT.STATUS} sort={sort} onSort={toggleSort}>
                  {t('tasks.detail.statusLabel')}
                </SortableHeader>
                <SortableHeader colKey={SORT.ASSIGNEE} sort={sort} onSort={toggleSort}>
                  {t('tasks.detail.assigneeLabel')}
                </SortableHeader>
                <SortableHeader colKey={SORT.URGENCY} sort={sort} onSort={toggleSort}>
                  {t('tasks.detail.urgencyLabel')}
                </SortableHeader>
                <SortableHeader colKey={SORT.DUE_DATE} sort={sort} onSort={toggleSort}>
                  {t('tasks.detail.dueDateLabel')}
                </SortableHeader>
                <SortableHeader colKey={SORT.TAG} sort={sort} onSort={toggleSort}>
                  {t('tasks.detail.tagLabel')}
                </SortableHeader>
                <SortableHeader colKey={SORT.CATEGORY} sort={sort} onSort={toggleSort}>
                  {t('tasks.detail.categoryLabel')}
                </SortableHeader>
                <SortableHeader colKey={SORT.HOURS} sort={sort} onSort={toggleSort} align="right">
                  {t('tasks.timeLogs.hoursLabel')}
                </SortableHeader>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 dark:divide-gray-700/60">
              {visibleTasks.length === 0 && (
                <tr>
                  <td colSpan={canManage ? 10 : 9} className="px-3 py-6 text-center text-sm text-gray-400 dark:text-gray-500">
                    {t('taskList.empty')}
                  </td>
                </tr>
              )}
              {visibleTasks.map((task, index) => {
                const overdue = isTaskOverdue(task)
                const hours = formatHours(task.totalHoursSpent)
                const selected = selectedIds.has(task.id)
                return (
                  <tr
                    key={task.id}
                    onClick={() => navigate(`/projects/${projectSlug}/tasks/${task.taskNumber}`)}
                    // Чередование фона строк — на широкой таблице глаз не теряет строку
                    // между колонкой «Название» и колонкой «Часы». Выделенная строка
                    // перебивает чередование: выделение должно читаться в любой позиции.
                    className={`cursor-pointer ${
                      selected
                        ? 'bg-purple-100 hover:bg-purple-100 dark:bg-purple-950/60 dark:hover:bg-purple-950/60'
                        : 'even:bg-gray-50/70 hover:bg-purple-50 dark:even:bg-gray-900/30 dark:hover:bg-purple-950/30'
                    }`}
                  >
                    {canManage && (
                      // Клик по галочке не должен открывать задачу: выделение и переход
                      // живут в одной строке, поэтому всплытие гасится на ячейке.
                      <td className={cellClass} onClick={(event) => event.stopPropagation()}>
                        <input
                          type="checkbox"
                          aria-label={t('taskList.bulk.selectRow', { number: task.taskNumber })}
                          checked={selected}
                          readOnly
                          onClick={(event) => toggleTask(index, event)}
                          className="h-4 w-4 accent-purple-600"
                        />
                      </td>
                    )}
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
                        <span className="block max-w-48 truncate text-gray-600 dark:text-gray-400">
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

      {!isLoading && !isError && (
        <Pagination page={data?.page ?? 0} totalPages={data?.totalPages ?? 0} onPageChange={setPage} />
      )}
    </div>
  )
}
