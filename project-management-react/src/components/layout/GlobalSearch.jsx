import { Search } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useProjectBySlug } from '../../api/projectsQueries'
import { useSearch } from '../../api/searchQueries'
import { searchResultPath } from '../../lib/searchResultPath'
import { useDebouncedValue } from '../../lib/useDebouncedValue'
import { SearchResultRow } from '../search/SearchResultRow'

// Сколько результатов показывает выпадашка. Это превью, а не выдача: остальное — на
// странице /search, куда ведёт последняя строка списка и Enter в поле.
const PREVIEW_SIZE = 5

/**
 * Строка поиска в шапке (4.1). Ищет по мере набора и показывает первые несколько
 * результатов сразу под полем.
 *
 * <p>Внутри проекта поиск сужается до него: искать в открытом проекте — это то, чего ждёшь
 * от поля в его же шапке, а до остальных проектов отсюда один переход. Вне проекта
 * (список проектов, создание проекта, страница /search) поиск глобальный.
 */
export function GlobalSearch() {
  const { t } = useTranslation()
  const navigate = useNavigate()

  const { projectSlug } = useParams()
  const { data: project } = useProjectBySlug(projectSlug)
  const projectId = project?.id ?? null
  // Пока проект по slug не разрешён, запрос не уходит: иначе первый запрос ушёл бы
  // глобальным и выдача на долю секунды показала бы чужие проекты.
  const scopeResolved = !projectSlug || Boolean(project)

  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const containerRef = useRef(null)

  const debouncedQuery = useDebouncedValue(query.trim())
  const { data, isFetching } = useSearch(
    projectId,
    { q: debouncedQuery, size: PREVIEW_SIZE },
    scopeResolved && debouncedQuery.length > 0,
  )

  useEffect(() => {
    function handleClickOutside(event) {
      if (containerRef.current && !containerRef.current.contains(event.target)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  function resultsPath() {
    const params = new URLSearchParams({ q: query.trim() })
    if (projectId) {
      params.set('projectId', projectId)
    }
    return `/search?${params}`
  }

  function handleSubmit(event) {
    event.preventDefault()
    if (!query.trim()) {
      return
    }
    setOpen(false)
    navigate(resultsPath())
  }

  const items = data?.items ?? []
  const showDropdown = open && debouncedQuery.length > 0

  return (
    <div ref={containerRef} className="relative order-last w-full min-w-0 sm:order-none sm:w-auto sm:max-w-sm sm:flex-1">
      <form onSubmit={handleSubmit} role="search">
        <label className="relative block">
          <span className="sr-only">{t('search.label')}</span>
          <Search
            className="pointer-events-none absolute top-1/2 left-2 h-4 w-4 -translate-y-1/2 text-gray-400"
            aria-hidden="true"
          />
          <input
            type="search"
            value={query}
            onChange={(event) => {
              setQuery(event.target.value)
              setOpen(true)
            }}
            onFocus={() => setOpen(true)}
            onKeyDown={(event) => {
              if (event.key === 'Escape') {
                setOpen(false)
              }
            }}
            placeholder={projectId ? t('search.placeholderProject') : t('search.placeholder')}
            className="w-full rounded-md border border-gray-300 bg-white py-1.5 pr-3 pl-8 text-sm text-gray-900 placeholder-gray-400 focus:border-purple-500 focus:ring-1 focus:ring-purple-500 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 dark:placeholder-gray-500"
          />
        </label>
      </form>

      {showDropdown && (
        <div className="absolute left-0 z-20 mt-2 w-[28rem] max-w-[calc(100vw-2rem)] overflow-hidden rounded-md border border-gray-200 bg-white shadow-lg dark:border-gray-700 dark:bg-gray-800">
          {items.length === 0 ? (
            <p className="px-4 py-3 text-sm text-gray-500 dark:text-gray-400">
              {isFetching ? t('search.searching') : t('search.empty', { query: debouncedQuery })}
            </p>
          ) : (
            <ul className="max-h-96 divide-y divide-gray-100 overflow-y-auto dark:divide-gray-700">
              {items.map((result) => (
                <li key={`${result.type}-${result.id}`}>
                  <Link
                    to={searchResultPath(result)}
                    onClick={() => setOpen(false)}
                    className="block px-4 py-2 hover:bg-gray-50 dark:hover:bg-gray-700"
                  >
                    <SearchResultRow result={result} compact showProject={!projectId} />
                  </Link>
                </li>
              ))}
            </ul>
          )}

          <Link
            to={resultsPath()}
            onClick={() => setOpen(false)}
            className="block border-t border-gray-200 px-4 py-2 text-center text-sm font-medium text-purple-700 hover:bg-gray-50 dark:border-gray-700 dark:text-purple-300 dark:hover:bg-gray-700"
          >
            {t('search.showAll', { total: data?.totalItems ?? 0 })}
          </Link>
        </div>
      )}
    </div>
  )
}
