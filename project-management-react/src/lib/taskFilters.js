import { TASK_DUE_FILTERS, TASK_STATUSES } from './constants'

// Состояние фильтров списка задач: чтение и запись URL, сборка параметров запроса,
// сравнение с сохранёнными представлениями (4.7, 5.5).
//
// Вынесено из страницы отдельным модулем по той же причине, что и bulkTasks.js: здесь
// живёт вся семантика «что считается тем же самым фильтром», а сломать её незаметно легко.
// Фильтры теперь одновременно лежат в трёх видах — в URL (строками, чтобы ссылку можно было
// отправить коллеге), в параметрах запроса (в том виде, в каком их ждёт бэкенд) и в
// сохранённом представлении (в том, в каком их вернул сервер), — и любые два из этих трёх
// обязаны сходиться, иначе представление перестаёт подсвечиваться активным ровно после
// того, как его применили.

// Сентинелы селекта исполнителя и категории. Пустая строка — «фильтр не задан», поэтому
// «мои» и «без исполнителя» нуждаются в своих значениях: на проводе это отдельные булевы
// флаги (assignedToMe/unassigned), а не id (см. TaskListQuery на бэкенде).
export const ASSIGNEE_ME = '__me__'
export const ASSIGNEE_UNASSIGNED = '__unassigned__'
export const CATEGORY_NONE = '__no_category__'
// «Бэклог» — задачи вне спринтов (4.9). Такой же сентинел, как «без категории», и по той
// же причине: на проводе это отдельный флаг noSprint, а не пустой id.
export const SPRINT_BACKLOG = '__backlog__'

export const DEFAULT_SORT = 'NUMBER'

export const SORT_KEYS = [
  'NUMBER',
  'TITLE',
  'STATUS',
  'ASSIGNEE',
  'URGENCY',
  'DUE_DATE',
  'TAG',
  'CATEGORY',
  'HOURS',
]

export const EMPTY_FILTERS = {
  search: '',
  status: '',
  assignee: '',
  tag: '',
  category: '',
  sprint: '',
  due: '',
  sort: DEFAULT_SORT,
  descending: false,
}

// Готовые представления, которые есть у всех и не хранятся нигде (4.7). Это ровно те три
// набора фильтров, которые названы в самом пункте, плюс «мои задачи» — они не про личные
// привычки, а про сам трекер, и заводить их каждому руками в каждом проекте значило бы
// сделать сохранённые представления обязательными к использованию, а не полезными.
// id — ключ перевода и ключ React-списка; в URL и на сервер он не уезжает никогда,
// применение пресета кладёт в адрес его фильтры (см. writeFilters).
export const PRESET_VIEWS = [
  { id: 'myTasks', filters: { assignee: ASSIGNEE_ME } },
  { id: 'myOverdue', filters: { assignee: ASSIGNEE_ME, due: 'OVERDUE' } },
  { id: 'unassigned', filters: { assignee: ASSIGNEE_UNASSIGNED } },
  { id: 'dueThisWeek', filters: { due: 'WEEK' } },
].map((preset) => ({ ...preset, filters: { ...EMPTY_FILTERS, ...preset.filters } }))

// Имена параметров в адресной строке коротки и человекочитаемы: ссылку на отфильтрованный
// список отправляют коллеге, а не парсеру. Внутри приложения и на проводе те же поля
// называются иначе (search/descending) — перевод между этими именами и есть работа модуля.
const PARAM = {
  search: 'q',
  status: 'status',
  assignee: 'assignee',
  tag: 'tag',
  category: 'category',
  sprint: 'sprint',
  due: 'due',
  sort: 'sort',
  direction: 'dir',
  page: 'page',
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

// Адресная строка — это пользовательский ввод: её правят руками, ей делятся урезанной
// мессенджером, она приезжает из закладки, сделанной до переименования тэга. Поэтому всё,
// что не входит в известный набор, молча становится «фильтр не задан»: показать список без
// одного фильтра лучше, чем показать 400 от сервера, которого человек не заказывал.
function readEnum(value, allowed, fallback = '') {
  return allowed.includes(value) ? value : fallback
}

function readId(value, sentinels = []) {
  if (!value) {
    return ''
  }
  if (sentinels.includes(value)) {
    return value
  }
  return UUID_PATTERN.test(value) ? value : ''
}

export function readFilters(searchParams) {
  return {
    search: searchParams.get(PARAM.search)?.trim() ?? '',
    status: readEnum(searchParams.get(PARAM.status), TASK_STATUSES),
    assignee: readId(searchParams.get(PARAM.assignee), [ASSIGNEE_ME, ASSIGNEE_UNASSIGNED]),
    tag: readId(searchParams.get(PARAM.tag)),
    category: readId(searchParams.get(PARAM.category), [CATEGORY_NONE]),
    sprint: readId(searchParams.get(PARAM.sprint), [SPRINT_BACKLOG]),
    due: readEnum(searchParams.get(PARAM.due), TASK_DUE_FILTERS),
    sort: readEnum(searchParams.get(PARAM.sort), SORT_KEYS, DEFAULT_SORT),
    descending: searchParams.get(PARAM.direction) === 'desc',
  }
}

// Номер страницы в адресе человеческий (первая — page=1), как в самой пагинации; внутри
// приложения и на бэкенде он по-прежнему с нуля.
export function readPage(searchParams) {
  const raw = Number(searchParams.get(PARAM.page))
  return Number.isInteger(raw) && raw > 1 ? raw - 1 : 0
}

/**
 * Фильтры в параметры адресной строки. Значения по умолчанию не пишутся вовсе: ссылка на
 * список без фильтров должна выглядеть как ссылка на список, а не как форма с десятью
 * пустыми полями.
 */
export function writeFilters(filters, page = 0) {
  const params = new URLSearchParams()
  if (filters.search) params.set(PARAM.search, filters.search)
  if (filters.status) params.set(PARAM.status, filters.status)
  if (filters.assignee) params.set(PARAM.assignee, filters.assignee)
  if (filters.tag) params.set(PARAM.tag, filters.tag)
  if (filters.category) params.set(PARAM.category, filters.category)
  if (filters.sprint) params.set(PARAM.sprint, filters.sprint)
  if (filters.due) params.set(PARAM.due, filters.due)
  if (filters.sort !== DEFAULT_SORT) params.set(PARAM.sort, filters.sort)
  if (filters.descending) params.set(PARAM.direction, 'desc')
  if (page > 0) params.set(PARAM.page, String(page + 1))
  return params
}

/**
 * Параметры запроса к GET /api/projects/{id}/tasks. Пустые поля не отправляются: на сервере
 * «параметра нет» и означает «фильтра нет», а лишние ключи ещё и попадают в ключ кэша
 * react-query, разводя один и тот же запрос по разным записям.
 */
export function toQueryParams(filters, page, pageSize) {
  const params = { sort: filters.sort, descending: filters.descending, page, size: pageSize }
  if (filters.search) params.search = filters.search
  if (filters.status) params.status = filters.status
  if (filters.assignee === ASSIGNEE_ME) params.assignedToMe = true
  else if (filters.assignee === ASSIGNEE_UNASSIGNED) params.unassigned = true
  else if (filters.assignee) params.assigneeId = filters.assignee
  if (filters.tag) params.tagId = filters.tag
  if (filters.category === CATEGORY_NONE) params.uncategorized = true
  else if (filters.category) params.categoryId = filters.category
  if (filters.sprint === SPRINT_BACKLOG) params.noSprint = true
  else if (filters.sprint) params.sprintId = filters.sprint
  if (filters.due) params.due = filters.due
  return params
}

export function filtersEqual(a, b) {
  return Object.keys(EMPTY_FILTERS).every((key) => a[key] === b[key])
}

export function hasAnyFilter(filters) {
  return !filtersEqual(filters, EMPTY_FILTERS)
}

/**
 * Сохранённое представление с сервера — в состояние фильтров.
 *
 * <p>Три булевых флага сервера (assignedToMe/unassigned/uncategorized) схлопываются в два
 * селекта интерфейса, и приоритет здесь тот же, что на бэкенде: «мои» сильнее «без
 * исполнителя», «без исполнителя» сильнее конкретного человека.
 */
export function savedViewToFilters(view) {
  let assignee = ''
  if (view.assignedToMe) assignee = ASSIGNEE_ME
  else if (view.unassigned) assignee = ASSIGNEE_UNASSIGNED
  else if (view.assigneeId) assignee = view.assigneeId

  return {
    search: view.search ?? '',
    status: view.status ?? '',
    assignee,
    tag: view.tagId ?? '',
    category: view.uncategorized ? CATEGORY_NONE : (view.categoryId ?? ''),
    sprint: view.noSprint ? SPRINT_BACKLOG : (view.sprintId ?? ''),
    due: view.due ?? '',
    sort: view.sort ?? DEFAULT_SORT,
    descending: Boolean(view.descending),
  }
}

/**
 * Состояние фильтров — в тело POST/PUT представления. Отправляется целиком, включая пустые
 * поля: сохранение это «запомни то, что сейчас на экране», и снятый фильтр обязан доехать
 * до сервера снятым (на той стороне PUT, а не PATCH, ровно по этой причине).
 */
export function filtersToSavedViewPayload(name, filters) {
  return {
    name,
    search: filters.search || null,
    status: filters.status || null,
    assigneeId:
      filters.assignee && filters.assignee !== ASSIGNEE_ME && filters.assignee !== ASSIGNEE_UNASSIGNED
        ? filters.assignee
        : null,
    unassigned: filters.assignee === ASSIGNEE_UNASSIGNED,
    assignedToMe: filters.assignee === ASSIGNEE_ME,
    tagId: filters.tag || null,
    categoryId: filters.category && filters.category !== CATEGORY_NONE ? filters.category : null,
    uncategorized: filters.category === CATEGORY_NONE,
    sprintId: filters.sprint && filters.sprint !== SPRINT_BACKLOG ? filters.sprint : null,
    noSprint: filters.sprint === SPRINT_BACKLOG,
    due: filters.due || null,
    sort: filters.sort,
    descending: filters.descending,
  }
}
