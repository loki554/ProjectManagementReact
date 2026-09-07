import { describe, expect, it } from 'vitest'
import {
  ASSIGNEE_ME,
  ASSIGNEE_UNASSIGNED,
  CATEGORY_NONE,
  EMPTY_FILTERS,
  SPRINT_BACKLOG,
  PRESET_VIEWS,
  filtersEqual,
  filtersToSavedViewPayload,
  hasAnyFilter,
  readFilters,
  readPage,
  savedViewToFilters,
  toQueryParams,
  writeFilters,
} from './taskFilters'

const TAG_ID = '3f2504e0-4f89-11d3-9a0c-0305e82c3301'
const CATEGORY_ID = '3f2504e0-4f89-11d3-9a0c-0305e82c3302'
const USER_ID = '3f2504e0-4f89-11d3-9a0c-0305e82c3303'
const SPRINT_ID = '3f2504e0-4f89-11d3-9a0c-0305e82c3304'

function params(query) {
  return new URLSearchParams(query)
}

function filters(overrides = {}) {
  return { ...EMPTY_FILTERS, ...overrides }
}

describe('readFilters', () => {
  it('пустой адрес — фильтров нет, сортировка по умолчанию', () => {
    expect(readFilters(params(''))).toEqual(EMPTY_FILTERS)
    expect(hasAnyFilter(readFilters(params('')))).toBe(false)
  })

  it('читает весь набор параметров', () => {
    const result = readFilters(
      params(`q=отчёт&status=IN_PROGRESS&assignee=${USER_ID}&tag=${TAG_ID}&category=${CATEGORY_ID}&sprint=${SPRINT_ID}&due=WEEK&sort=DUE_DATE&dir=desc`),
    )
    expect(result).toEqual({
      search: 'отчёт',
      status: 'IN_PROGRESS',
      assignee: USER_ID,
      tag: TAG_ID,
      category: CATEGORY_ID,
      sprint: SPRINT_ID,
      due: 'WEEK',
      sort: 'DUE_DATE',
      descending: true,
    })
  })

  it('сентинелы «мои» и «без исполнителя»/«без категории» доезжают как есть', () => {
    expect(readFilters(params('assignee=__me__')).assignee).toBe(ASSIGNEE_ME)
    expect(readFilters(params('assignee=__unassigned__')).assignee).toBe(ASSIGNEE_UNASSIGNED)
    expect(readFilters(params('category=__no_category__')).category).toBe(CATEGORY_NONE)
    expect(readFilters(params('sprint=__backlog__')).sprint).toBe(SPRINT_BACKLOG)
  })

  // Адрес правят руками и присылают в мессенджере — мусор в нём должен означать «фильтра
  // нет», а не 400 от сервера, которого человек не заказывал.
  it('неизвестные значения игнорируются', () => {
    const result = readFilters(params('status=BANANA&due=YESTERDAY&sort=RANDOM&assignee=not-a-uuid&tag=42'))
    expect(result).toEqual(EMPTY_FILTERS)
  })

  it('dir учитывается только как desc', () => {
    expect(readFilters(params('dir=asc')).descending).toBe(false)
    expect(readFilters(params('dir=desc')).descending).toBe(true)
  })
})

describe('readPage', () => {
  // В адресе страница человеческая (первая — page=1), внутри приложения — с нуля.
  it('нумерация в адресе с единицы, внутри — с нуля', () => {
    expect(readPage(params(''))).toBe(0)
    expect(readPage(params('page=1'))).toBe(0)
    expect(readPage(params('page=3'))).toBe(2)
  })

  it('мусор и нулевые/отрицательные страницы — первая страница', () => {
    expect(readPage(params('page=0'))).toBe(0)
    expect(readPage(params('page=-2'))).toBe(0)
    expect(readPage(params('page=abc'))).toBe(0)
    expect(readPage(params('page=1.5'))).toBe(0)
  })
})

describe('writeFilters', () => {
  it('значения по умолчанию в адрес не пишутся', () => {
    expect(writeFilters(EMPTY_FILTERS, 0).toString()).toBe('')
  })

  it('пишет только заданные фильтры', () => {
    const query = writeFilters(filters({ status: 'NEW', assignee: ASSIGNEE_ME, due: 'OVERDUE' }), 0)
    expect(query.toString()).toBe('status=NEW&assignee=__me__&due=OVERDUE')
  })

  it('сортировка по умолчанию не пишется, а обратная — пишется', () => {
    expect(writeFilters(filters({ sort: 'NUMBER', descending: true }), 0).toString()).toBe('dir=desc')
    expect(writeFilters(filters({ sort: 'TITLE' }), 0).toString()).toBe('sort=TITLE')
  })

  // Главное свойство пары чтение/запись: ссылка, отправленная коллеге, открывает у него
  // ровно тот же список.
  it('прочитанное обратно записывается тем же самым', () => {
    const original = filters({
      search: 'отчёт',
      status: 'DONE',
      assignee: ASSIGNEE_UNASSIGNED,
      tag: TAG_ID,
      category: CATEGORY_NONE,
      sprint: SPRINT_ID,
      due: 'TODAY',
      sort: 'HOURS',
      descending: true,
    })
    const roundTripped = readFilters(writeFilters(original, 4))
    expect(roundTripped).toEqual(original)
    expect(readPage(writeFilters(original, 4))).toBe(4)
  })
})

describe('toQueryParams', () => {
  it('пустые фильтры не отправляются на сервер', () => {
    expect(toQueryParams(EMPTY_FILTERS, 0, 50)).toEqual({
      sort: 'NUMBER',
      descending: false,
      page: 0,
      size: 50,
    })
  })

  // «Мои», «без исполнителя» и «без категории» — булевы флаги, а не id: пустой id на
  // сервере означает «фильтр не задан».
  it('сентинелы превращаются во флаги, а не в id', () => {
    expect(toQueryParams(filters({ assignee: ASSIGNEE_ME }), 0, 50)).toMatchObject({ assignedToMe: true })
    expect(toQueryParams(filters({ assignee: ASSIGNEE_ME }), 0, 50)).not.toHaveProperty('assigneeId')
    expect(toQueryParams(filters({ assignee: ASSIGNEE_UNASSIGNED }), 0, 50)).toMatchObject({ unassigned: true })
    expect(toQueryParams(filters({ category: CATEGORY_NONE }), 0, 50)).toMatchObject({ uncategorized: true })
    // «Бэклог» (4.9) устроен так же: это выбранный пункт фильтра, а не пустой спринт.
    expect(toQueryParams(filters({ sprint: SPRINT_BACKLOG }), 0, 50)).toMatchObject({ noSprint: true })
    expect(toQueryParams(filters({ sprint: SPRINT_BACKLOG }), 0, 50)).not.toHaveProperty('sprintId')
  })

  it('обычный исполнитель, категория и спринт едут id-шниками', () => {
    expect(toQueryParams(filters({ assignee: USER_ID, category: CATEGORY_ID, sprint: SPRINT_ID }), 0, 50)).toMatchObject({
      assigneeId: USER_ID,
      categoryId: CATEGORY_ID,
      sprintId: SPRINT_ID,
    })
  })
})

describe('сохранённые представления', () => {
  it('ответ сервера разворачивается в те же фильтры, из которых собран', () => {
    const original = filters({ search: 'счёт', assignee: ASSIGNEE_ME, due: 'OVERDUE', sort: 'DUE_DATE' })
    const payload = filtersToSavedViewPayload('Мои просроченные', original)
    // Сервер отдаёт то же тело плюс id/имя — этого достаточно, чтобы проверить круг.
    expect(savedViewToFilters(payload)).toEqual(original)
  })

  it('«мои» сохраняются флагом, а не id автора: иначе у коллеги это были бы чужие задачи', () => {
    const payload = filtersToSavedViewPayload('Мои', filters({ assignee: ASSIGNEE_ME }))
    expect(payload.assignedToMe).toBe(true)
    expect(payload.assigneeId).toBeNull()
    expect(payload.unassigned).toBe(false)
  })

  it('снятый фильтр уезжает на сервер снятым, а не отсутствующим', () => {
    const payload = filtersToSavedViewPayload('Пусто', EMPTY_FILTERS)
    expect(payload).toMatchObject({
      search: null,
      status: null,
      assigneeId: null,
      unassigned: false,
      assignedToMe: false,
      tagId: null,
      categoryId: null,
      uncategorized: false,
      sprintId: null,
      noSprint: false,
      due: null,
    })
  })

  it('приоритет флагов над значением такой же, как на бэкенде', () => {
    expect(savedViewToFilters({ assignedToMe: true, unassigned: true, assigneeId: USER_ID }).assignee).toBe(
      ASSIGNEE_ME,
    )
    expect(savedViewToFilters({ unassigned: true, assigneeId: USER_ID }).assignee).toBe(ASSIGNEE_UNASSIGNED)
    expect(savedViewToFilters({ uncategorized: true, categoryId: CATEGORY_ID }).category).toBe(CATEGORY_NONE)
    expect(savedViewToFilters({ noSprint: true, sprintId: SPRINT_ID }).sprint).toBe(SPRINT_BACKLOG)
  })

  // Ради чего фильтр по спринту вообще заведён в представлениях: «мои задачи в текущем
  // спринте» должно ложиться на кнопку и переживать перезагрузку.
  it('спринт сохраняется в представлении и разворачивается обратно', () => {
    const original = filters({ assignee: ASSIGNEE_ME, sprint: SPRINT_ID })
    const payload = filtersToSavedViewPayload('Мои в спринте', original)
    expect(payload.sprintId).toBe(SPRINT_ID)
    expect(payload.noSprint).toBe(false)
    expect(savedViewToFilters(payload)).toEqual(original)
  })

  it('представление без единого фильтра читается как пустой набор', () => {
    expect(savedViewToFilters({ name: 'Всё' })).toEqual(EMPTY_FILTERS)
  })
})

describe('filtersEqual', () => {
  it('сравнивает наборы, а не ссылки — иначе применённое представление не подсветилось бы', () => {
    expect(filtersEqual(filters({ due: 'WEEK' }), filters({ due: 'WEEK' }))).toBe(true)
    expect(filtersEqual(filters({ due: 'WEEK' }), filters({ due: 'TODAY' }))).toBe(false)
  })

  it('различие только в сортировке — это разные представления', () => {
    expect(filtersEqual(EMPTY_FILTERS, filters({ descending: true }))).toBe(false)
  })
})

describe('PRESET_VIEWS', () => {
  // Пресеты — это ровно те наборы, что названы в пункте 4.7; они не хранятся нигде и
  // должны выражаться теми же полями, что и любой другой фильтр.
  it('каждый пресет — полный набор фильтров', () => {
    for (const preset of PRESET_VIEWS) {
      expect(Object.keys(preset.filters).sort()).toEqual(Object.keys(EMPTY_FILTERS).sort())
      expect(hasAnyFilter(preset.filters)).toBe(true)
    }
  })

  it('«мои просроченные» — это «мои» плюс окно OVERDUE', () => {
    const preset = PRESET_VIEWS.find((view) => view.id === 'myOverdue')
    expect(toQueryParams(preset.filters, 0, 50)).toMatchObject({ assignedToMe: true, due: 'OVERDUE' })
  })
})
