import { useTranslation } from 'react-i18next'
import { TASK_NUMBER_BADGE_CLASS } from '../../lib/constants'
import { SearchSnippet } from '../../lib/searchSnippet'

// Один и тот же вид карточки в выпадашке шапки и на странице /search: результат — это
// результат, и раздваивать его вёрстку значило бы однажды поправить только одну половину.
// Отличается только плотность (compact) и нужно ли подписывать проект.
const TYPE_BADGE_CLASS = {
  TASK: 'bg-blue-100 text-blue-800 dark:bg-blue-900/60 dark:text-blue-200',
  COMMENT: 'bg-amber-100 text-amber-800 dark:bg-amber-900/60 dark:text-amber-200',
  WIKI: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/60 dark:text-emerald-200',
}

export function SearchResultRow({ result, compact = false, showProject = true }) {
  const { t } = useTranslation()

  return (
    <div className="min-w-0">
      <div className="flex flex-wrap items-center gap-2">
        <span
          className={`rounded px-1.5 py-0.5 text-xs font-medium ${TYPE_BADGE_CLASS[result.type]}`}
        >
          {t(`search.resultTypes.${result.type}`)}
        </span>

        {/* У вики нет задачи — её заголовок это сам проект, поэтому подпись проекта ниже
            была бы повтором. */}
        {result.type === 'WIKI' ? (
          <span className="truncate text-sm font-medium text-gray-900 dark:text-gray-100">
            {t('search.wikiTitle', { project: result.projectName })}
          </span>
        ) : (
          <>
            <span className={TASK_NUMBER_BADGE_CLASS}>#{result.taskNumber}</span>
            <span className="truncate text-sm font-medium text-gray-900 dark:text-gray-100">
              {result.taskTitle}
            </span>
          </>
        )}

        {showProject && result.type !== 'WIKI' && (
          <span className="truncate text-xs text-gray-500 dark:text-gray-400">
            {result.projectName}
          </span>
        )}
      </div>

      {/* У задачи без описания сниппету взяться неоткуда (совпало название) — пустой
          абзац в этом случае только добавил бы строку пустоты. */}
      {result.snippet && (
        <p
          className={`mt-1 text-sm text-gray-600 dark:text-gray-400 ${
            compact ? 'line-clamp-2' : ''
          }`}
        >
          <SearchSnippet text={result.snippet} />
        </p>
      )}
    </div>
  )
}
