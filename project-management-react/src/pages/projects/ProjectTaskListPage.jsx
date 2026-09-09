import { OctagonAlert } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useProjectBySlug, useProjectMembers } from '../../api/projectsQueries'
import { useCategories } from '../../api/categoriesQueries'
import { useSprints } from '../../api/sprintsQueries'
import {
  useCreateSavedView,
  useDeleteSavedView,
  useSavedViews,
  useUpdateSavedView,
} from '../../api/savedViewsQueries'
import { useTags } from '../../api/tagsQueries'
import { useBulkUpdateTasks, useTasks } from '../../api/tasksQueries'
import { BulkActionsBar } from '../../components/tasks/BulkActionsBar'
import { SavedViewsBar } from '../../components/tasks/SavedViewsBar'
import { Pagination } from '../../components/ui/Pagination'
import { UserAvatar } from '../../components/ui/UserAvatar'
import { inputClass, primaryButtonClass } from '../../components/ui/FormKit'
import {
  TASK_DUE_FILTERS,
  TASK_NUMBER_BADGE_CLASS,
  TASK_STATUSES,
  canWriteInProject,
  taskStatusBadgeClass,
  taskUrgencyBadgeClass,
} from '../../lib/constants'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { blockedTaskNumbers, formatTaskNumbers, isOpenBlockersError } from '../../lib/taskBlockers'
import { tagBadgeStyle } from '../../lib/tagColor'
import { assigneeLabelOf, formatDueDate, formatHours, isTaskOverdue } from '../../lib/taskDisplay'
import {
  ASSIGNEE_ME,
  ASSIGNEE_UNASSIGNED,
  CATEGORY_NONE,
  SORT_KEYS,
  SPRINT_BACKLOG,
  filtersEqual,
  filtersToSavedViewPayload,
  readFilters,
  readPage,
  savedViewToFilters,
  toQueryParams,
  writeFilters,
} from '../../lib/taskFilters'
import { useDebouncedValue } from '../../lib/useDebouncedValue'
import { useAuthStore } from '../../stores/authStore'
import { useToastStore } from '../../stores/toastStore'

const PAGE_SIZE = 50

// Ключи сортировки — это значения TaskSortKey на бэкенде: и порядок, и фильтрация теперь
// считаются в БД (3.3). Клиентские компараторы, стоявшие здесь раньше, работали по
// загруженному массиву и с постраничной выдачей давали бы отсортированную страницу
// вместо первой страницы отсортированного списка.
//
// Именованный доступ к тому же списку, по которому разбирается адресная строка
// (SORT_KEYS): второй перечень здесь означал бы ключ, по которому таблица сортирует, а
// ссылка на неё — уже нет.
const SORT = Object.fromEntries(SORT_KEYS.map((key) => [key, key]))

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
  const { data: sprints } = useSprints(projectId)

  const myMembership = members?.find((member) => member.userId === currentUser?.id)
  const canManage = canWriteInProject(project, myMembership?.role, 'MEMBER')

  // Фильтры, сортировка и номер страницы живут в адресной строке (5.5, 4.7), а не в
  // useState: отфильтрованный список должен переживать перезагрузку и уезжать коллеге
  // ссылкой — именно из этого состоит «шаринг» сохранённых представлений.
  const [searchParams, setSearchParams] = useSearchParams()
  // Ключ — строка адреса: URLSearchParams при каждом рендере новый объект, и мемоизация
  // по нему самому не мемоизировала бы ничего.
  const searchParamsKey = searchParams.toString()
  const filters = useMemo(() => readFilters(searchParams), [searchParamsKey])
  const page = readPage(searchParams)
  const sort = useMemo(() => ({ key: filters.sort, dir: filters.descending ? -1 : 1 }), [filters])

  // Адрес переписывается через replace, а не push. Иначе каждый выбранный фильтр — шаг
  // истории, и «назад» из списка означало бы не «вернуться откуда пришёл», а пройти обратно
  // всю возню с фильтрами. Ссылкой при этом делятся адресом, а не историей, и он всегда
  // актуален; возврат на список из открытой задачи тоже приводит к последнему состоянию.
  function applyFilters(next, nextPage = 0) {
    setSearchParams(writeFilters(next, nextPage), { replace: true })
  }

  // Смена одного фильтра человеком: страница сбрасывается на первую (остаться на седьмой
  // странице выдачи, в которой теперь две, значит увидеть пустую таблицу вместо
  // результата), а привязка к открытому представлению сохраняется — на ней держится
  // кнопка «Обновить».
  function setFilter(patch) {
    applyFilters({ ...filters, ...patch })
  }

  // Ввод в поиске уходит на сервер, поэтому не на каждый символ — и в адрес тоже: писать
  // в историю по символу означало бы полсотни записей на одно слово.
  const [searchInput, setSearchInput] = useState(filters.search)
  const debouncedSearch = useDebouncedValue(searchInput.trim())

  useEffect(() => {
    if (debouncedSearch !== filters.search) {
      setFilter({ search: debouncedSearch })
    }
  }, [debouncedSearch])

  // Обратная сторона: источник истины — адрес, поэтому фильтры, приехавшие снаружи (кнопка
  // представления, ссылка от коллеги, «назад» браузера), подхватываются полем ввода.
  useEffect(() => {
    setSearchInput(filters.search)
  }, [filters.search])

  const params = useMemo(() => toQueryParams(filters, page, PAGE_SIZE), [searchParamsKey])

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

  /**
   * Массовая правка (4.6) с подтверждением закрытия заблокированных задач (4.8).
   *
   * Вопрос задаётся до запроса, а не по 409 с сервера, — в отличие от доски и формы
   * задачи. Причина в том, что тут спрашивают не «точно закрываем эту?», а «точно
   * закрываем вот эти три из двадцати?»: номера нужны в самом вопросе, и взять их можно
   * только из таблицы на экране — тело ошибки у бэкенда одно на все случаи, {error,
   * message}, и разбирать номера из текста сообщения было бы худшей из возможных связей
   * между фронтендом и сервером. Список на экране мог устареть — тогда тот же отказ
   * придёт с сервера, и он разбирается вторым, «немым» подтверждением.
   */
  function applyBulk(payload, ignoreBlockers = false) {
    const taskIds = [...selectedIds]
    if (!ignoreBlockers && payload.status === 'DONE') {
      const blocked = blockedTaskNumbers(visibleTasks, taskIds)
      if (blocked.length > 0) {
        if (!window.confirm(t('tasks.dependencies.bulkDoneConfirm', { tasks: formatTaskNumbers(blocked) }))) {
          return
        }
        applyBulk(payload, true)
        return
      }
    }
    bulkUpdate.mutate(
      { ...payload, taskIds, ignoreBlockers },
      {
        onSuccess: (result) => {
          pushToast(t('taskList.bulk.applied', { count: result.updated }))
          setSelectedIds(new Set())
        },
        onError: (error) => {
          if (!ignoreBlockers && isOpenBlockersError(error) && window.confirm(t('tasks.dependencies.doneConfirm'))) {
            applyBulk(payload, true)
          }
        },
      },
    )
  }

  function toggleSort(key) {
    setFilter(
      key === filters.sort
        ? { descending: !filters.descending }
        : { sort: key, descending: false },
    )
  }

  // ---------------------------------------------------------------- представления (4.7)

  const { data: savedViewsData } = useSavedViews(projectId)
  const createView = useCreateSavedView(projectId)
  const updateView = useUpdateSavedView(projectId)
  const deleteView = useDeleteSavedView(projectId)
  const [appliedViewId, setAppliedViewId] = useState(null)

  // Панель не должна знать про формат ответа сервера: она сравнивает наборы фильтров и
  // показывает имена, поэтому DTO разворачивается в фильтры здесь.
  const savedViews = useMemo(
    () => savedViewsData?.map((view) => ({ id: view.id, name: view.name, filters: savedViewToFilters(view) })) ?? [],
    [savedViewsData],
  )
  const appliedView = savedViews.find((view) => view.id === appliedViewId) ?? null

  // Представление, совпавшее с тем, что сейчас в адресе, считается открытым — иначе после
  // перезагрузки страницы (или перехода по присланной ссылке) кнопка «Обновить» не
  // появилась бы, хотя представление на экране подсвечено активным.
  useEffect(() => {
    const matched = savedViews.find((view) => filtersEqual(filters, view.filters))
    if (matched && matched.id !== appliedViewId) {
      setAppliedViewId(matched.id)
    }
  }, [savedViews, filters])

  function applyView(nextFilters, viewId) {
    applyFilters(nextFilters)
    setSearchInput(nextFilters.search)
    setAppliedViewId(viewId)
  }

  function handleSaveView(name, done) {
    createView.mutate(filtersToSavedViewPayload(name, filters), {
      onSuccess: (view) => {
        setAppliedViewId(view.id)
        pushToast(t('taskList.views.saved', { name: view.name }))
        done()
      },
    })
  }

  function handleUpdateView(view) {
    updateView.mutate(
      { viewId: view.id, ...filtersToSavedViewPayload(view.name, filters) },
      { onSuccess: () => pushToast(t('taskList.views.updated', { name: view.name })) },
    )
  }

  function handleDeleteView(view) {
    deleteView.mutate(view.id, {
      onSuccess: () => {
        setAppliedViewId((current) => (current === view.id ? null : current))
        pushToast(t('taskList.views.deleted', { name: view.name }))
      },
    })
  }

  // Ссылка на список — это его адрес целиком: фильтры в нём уже есть, копировать нужно
  // ровно то, что видно в адресной строке. Кнопка существует потому, что «скопируйте из
  // адресной строки» — это ровно тот шаг, на котором люди перестают делиться ссылками.
  function handleCopyLink() {
    // clipboard недоступен в небезопасном контексте (http на не-localhost); показать в
    // этом случае «скопируйте вручную» честнее, чем промолчать.
    if (!navigator.clipboard) {
      pushToast(t('taskList.views.copyLinkUnavailable'))
      return
    }
    navigator.clipboard
      .writeText(window.location.href)
      .then(() => pushToast(t('taskList.views.linkCopied')))
      .catch(() => pushToast(t('taskList.views.copyLinkUnavailable')))
  }

  return (
    <div className="flex h-full flex-col gap-3 px-4 py-4">
      {/* Без flex-wrap: у полей из FormKit ширина w-full, и перенос строк раскладывает
          панель фильтров в вертикальный столбец во весь экран. В одну строку они делят
          её между собой, как делили и до появления шестого фильтра. */}
      <div className="flex items-center gap-3">
        <input
          type="search"
          value={searchInput}
          onChange={(event) => setSearchInput(event.target.value)}
          placeholder={t('taskList.searchPlaceholder')}
          className={`${inputClass} w-64 min-w-0`}
        />
        <select
          value={filters.status}
          onChange={(event) => setFilter({ status: event.target.value })}
          className={`${inputClass} w-44 min-w-0`}
        >
          <option value="">{t('taskList.allStatuses')}</option>
          {TASK_STATUSES.map((status) => (
            <option key={status} value={status}>
              {t(`tasks.status.${status}`)}
            </option>
          ))}
        </select>
        <select
          value={filters.assignee}
          onChange={(event) => setFilter({ assignee: event.target.value })}
          className={`${inputClass} w-52 min-w-0`}
        >
          <option value="">{t('taskList.allAssignees')}</option>
          {/* «Мои» стоит отдельным пунктом над списком участников, а не выбором себя в нём:
              в сохранённом представлении это разные вещи — «задачи Иванова» остаются
              задачами Иванова у всех, а «мои» у каждого свои (см. taskFilters). */}
          <option value={ASSIGNEE_ME}>{t('taskList.assignedToMe')}</option>
          <option value={ASSIGNEE_UNASSIGNED}>{t('tasks.unassigned')}</option>
          {members?.map((member) => (
            <option key={member.userId} value={member.userId}>
              {member.lastName} {member.firstName}
            </option>
          ))}
        </select>
        <select
          value={filters.due}
          onChange={(event) => setFilter({ due: event.target.value })}
          className={`${inputClass} w-44 min-w-0`}
        >
          <option value="">{t('taskList.allDueDates')}</option>
          {TASK_DUE_FILTERS.map((due) => (
            <option key={due} value={due}>
              {t(`taskList.due.${due}`)}
            </option>
          ))}
        </select>
        <select
          value={filters.tag}
          onChange={(event) => setFilter({ tag: event.target.value })}
          className={`${inputClass} w-44 min-w-0`}
        >
          <option value="">{t('taskList.allTags')}</option>
          {tags?.map((tag) => (
            <option key={tag.id} value={tag.id}>
              {tag.name}
            </option>
          ))}
        </select>
        <select
          value={filters.category}
          onChange={(event) => setFilter({ category: event.target.value })}
          className={`${inputClass} w-52 min-w-0`}
        >
          <option value="">{t('taskList.allCategories')}</option>
          <option value={CATEGORY_NONE}>{t('tasks.noCategory')}</option>
          {categories?.map((category) => (
            <option key={category.id} value={category.id}>
              {category.name}
            </option>
          ))}
        </select>
        {/* Спринт (4.9) — последний в ряду фильтров: он появился позже остальных, а
            привычка искать фильтр на прежнем месте дороже алфавитного порядка. */}
        <select
          value={filters.sprint}
          onChange={(event) => setFilter({ sprint: event.target.value })}
          className={`${inputClass} w-48 min-w-0`}
        >
          <option value="">{t('taskList.allSprints')}</option>
          <option value={SPRINT_BACKLOG}>{t('sprints.backlogTitle')}</option>
          {sprints?.map((sprint) => (
            <option key={sprint.id} value={sprint.id}>
              {sprint.name}
            </option>
          ))}
        </select>
        {canManage && (
          <Link to={`/projects/${projectSlug}/tasks/new`} className={`${primaryButtonClass} shrink-0 whitespace-nowrap`}>
            + {t('taskList.newTask')}
          </Link>
        )}
        <span className="ml-auto shrink-0 text-sm whitespace-nowrap text-gray-500 dark:text-gray-400">
          {t('taskList.total', { count: data?.totalItems ?? 0 })}
        </span>
      </div>

      {/* Панель представлений — под фильтрами, а не над ними: она их применяет, и читается
          это сверху вниз («вот фильтры — вот наборы фильтров, сохранённые под именем»). */}
      <SavedViewsBar
        filters={filters}
        savedViews={savedViews}
        appliedView={appliedView}
        onApply={applyView}
        onSave={handleSaveView}
        onUpdate={handleUpdateView}
        onDelete={handleDeleteView}
        onCopyLink={handleCopyLink}
        isSaving={createView.isPending}
        error={createView.error ?? updateView.error ?? deleteView.error}
      />

      {canManage && selectedIds.size > 0 && (
        <BulkActionsBar
          selectedCount={selectedIds.size}
          members={members}
          tags={tags}
          sprints={sprints}
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
                      <span className="flex items-center gap-1 whitespace-nowrap">
                        <span className={TASK_NUMBER_BADGE_CLASS}>#{task.taskNumber}</span>
                        {/* Незакрытые блокеры (4.8) — рядом с номером, а не отдельной
                            колонкой: колонок в таблице уже девять, а признак нужен ровно
                            там, где глаз ищет задачу. */}
                        {task.openBlockerCount > 0 && task.status !== 'DONE' && (
                          <OctagonAlert
                            role="img"
                            className="h-4 w-4 shrink-0 text-amber-500"
                            aria-label={t('tasks.dependencies.blockedBadge', { count: task.openBlockerCount })}
                          />
                        )}
                        {/* Прогресс чек-листа (4.13) — там же и по той же причине: это
                            признак задачи, а не десятая колонка таблицы. */}
                        {task.checklistTotal > 0 && (
                          <span
                            className="shrink-0 rounded-full bg-gray-100 px-1.5 py-0.5 text-xs font-medium text-gray-600 dark:bg-gray-700 dark:text-gray-300"
                            title={t('checklist.badgeHint')}
                          >
                            {task.checklistDone}/{task.checklistTotal}
                          </span>
                        )}
                      </span>
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
        <Pagination
          page={data?.page ?? 0}
          totalPages={data?.totalPages ?? 0}
          onPageChange={(nextPage) => applyFilters(filters, nextPage)}
        />
      )}
    </div>
  )
}
