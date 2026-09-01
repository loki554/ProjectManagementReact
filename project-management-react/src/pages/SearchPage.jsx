import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router-dom'
import { useProjects } from '../api/projectsQueries'
import { useSearch } from '../api/searchQueries'
import { AppHeader } from '../components/layout/AppHeader'
import { SearchResultRow } from '../components/search/SearchResultRow'
import { Pagination } from '../components/ui/Pagination'
import { inputClass } from '../components/ui/FormKit'
import { getLocalizedErrorMessage } from '../lib/errorMessage'
import { searchResultPath } from '../lib/searchResultPath'
import { useDebouncedValue } from '../lib/useDebouncedValue'

const PAGE_SIZE = 20
const TYPES = ['TASK', 'COMMENT', 'WIKI']

/**
 * Страница выдачи поиска (4.1).
 *
 * <p>Всё состояние живёт в query-параметрах URL, а не в useState: ссылку на выдачу должно
 * быть можно отправить коллеге и вернуть кнопкой «назад» браузера. Заодно это единственный
 * способ прийти сюда из шапки с уже проставленным запросом и областью поиска.
 */
export function SearchPage() {
  const { t } = useTranslation()
  const [searchParams, setSearchParams] = useSearchParams()
  const { data: projects } = useProjects()

  const query = searchParams.get('q') ?? ''
  const type = searchParams.get('type') ?? ''
  const projectId = searchParams.get('projectId') ?? ''
  const page = Number(searchParams.get('page') ?? 0)

  // Поле управляется прямо из URL, а запрос на сервер — отложенный: иначе каждый символ
  // это запрос (та же причина, что в списке задач).
  const debouncedQuery = useDebouncedValue(query.trim())

  const params = { q: debouncedQuery, page, size: PAGE_SIZE }
  if (type) {
    params.type = type
  }

  const { data, isFetching, isError, error } = useSearch(
    projectId || null,
    params,
    debouncedQuery.length > 0,
  )

  // Любая смена запроса, области или типа меняет состав выдачи: остаться на третьей
  // странице, которой больше нет, значит увидеть пустоту вместо результата (то же
  // правило, что в списке задач).
  function update(changes) {
    const next = new URLSearchParams(searchParams)
    for (const [key, value] of Object.entries(changes)) {
      if (value) {
        next.set(key, value)
      } else {
        next.delete(key)
      }
    }
    if (!('page' in changes)) {
      next.delete('page')
    }
    setSearchParams(next, { replace: true })
  }

  const items = data?.items ?? []
  const hasQuery = query.trim().length > 0

  return (
    <div className="min-h-svh">
      <AppHeader />

      <div className="mx-auto max-w-4xl px-4 py-8">
        <h1 className="mb-6 text-2xl font-semibold text-gray-900 dark:text-gray-100">
          {t('search.title')}
        </h1>

        <div className="mb-4 flex flex-wrap gap-3">
          <input
            type="search"
            value={query}
            onChange={(event) => update({ q: event.target.value })}
            placeholder={t('search.placeholder')}
            aria-label={t('search.label')}
            className={`${inputClass} min-w-48 flex-1`}
          />
          <select
            value={projectId}
            onChange={(event) => update({ projectId: event.target.value })}
            aria-label={t('search.scopeLabel')}
            className={`${inputClass} w-auto`}
          >
            <option value="">{t('search.allProjects')}</option>
            {projects?.map((project) => (
              <option key={project.id} value={project.id}>
                {project.name}
              </option>
            ))}
          </select>
        </div>

        {/* Тип — вкладки, а не ещё один селект: их всего четыре, и переключаться между
            ними в выдаче приходится чаще, чем менять проект. */}
        <div className="mb-6 flex flex-wrap gap-1 border-b border-gray-200 dark:border-gray-700">
          {['', ...TYPES].map((value) => (
            <button
              key={value || 'ALL'}
              type="button"
              onClick={() => update({ type: value })}
              className={`-mb-px border-b-2 px-3 py-2 text-sm font-medium ${
                type === value
                  ? 'border-purple-600 text-purple-700 dark:text-purple-300'
                  : 'border-transparent text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200'
              }`}
            >
              {value ? t(`search.types.${value}`) : t('search.types.ALL')}
            </button>
          ))}
        </div>

        {!hasQuery && <p className="text-gray-500 dark:text-gray-400">{t('search.hint')}</p>}

        {isError && (
          <p className="text-sm text-red-600 dark:text-red-400">
            {getLocalizedErrorMessage(error, t)}
          </p>
        )}

        {hasQuery && !isError && (
          <>
            <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">
              {isFetching && !data
                ? t('search.searching')
                : t('search.found', { total: data?.totalItems ?? 0 })}
            </p>

            {items.length === 0 && !isFetching && (
              <p className="rounded-lg border border-dashed border-gray-300 bg-white py-12 text-center text-gray-600 dark:border-gray-600 dark:bg-gray-800 dark:text-gray-400">
                {t('search.empty', { query: debouncedQuery })}
              </p>
            )}

            <ul className="space-y-2">
              {items.map((result) => (
                <li key={`${result.type}-${result.id}`}>
                  <Link
                    to={searchResultPath(result)}
                    className="block rounded-lg border border-gray-200 bg-white p-3 hover:border-purple-300 hover:bg-gray-50 dark:border-gray-700 dark:bg-gray-800 dark:hover:border-purple-700 dark:hover:bg-gray-700"
                  >
                    <SearchResultRow result={result} showProject={!projectId} />
                  </Link>
                </li>
              ))}
            </ul>

            <Pagination
              page={page}
              totalPages={data?.totalPages ?? 0}
              onPageChange={(next) => update({ page: String(next) })}
            />
          </>
        )}
      </div>
    </div>
  )
}
