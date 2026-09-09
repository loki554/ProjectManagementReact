import { RotateCw, TriangleAlert } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { primaryButtonClass, secondaryButtonClass } from '../ui/FormKit'

/**
 * Текст ошибки показываем только в dev. Пользователю «Cannot read properties of undefined»
 * не объясняет ничего и выглядит как утечка внутренностей; разработчику без него граница
 * из помощи превращается в помеху — она проглатывает ровно тот экран, на который он смотрит,
 * а сообщение в консоли теряется за перерисовками.
 */
function ErrorDetails({ error }) {
  const { t } = useTranslation()
  if (!import.meta.env.DEV) {
    return null
  }
  return (
    <details className="w-full max-w-2xl text-left">
      <summary className="cursor-pointer text-xs text-gray-500 dark:text-gray-400">
        {t('errorBoundary.details')}
      </summary>
      <pre className="mt-2 max-h-64 overflow-auto rounded bg-gray-100 p-3 text-xs text-gray-700 dark:bg-gray-800 dark:text-gray-300">
        {String(error?.stack ?? error)}
      </pre>
    </details>
  )
}

/**
 * Экран «всё сломалось» — для корневой и маршрутной границ.
 *
 * onRetry передаёт только маршрутная: на корневой перемонтирование дерева, которое только
 * что упало, честнее назвать перезагрузкой, чем притворяться, что это разные действия.
 *
 * «К проектам» — обычная ссылка, а не <Link>, намеренно: этот же экран рендерится и выше
 * BrowserRouter (корневая граница), где никакого роутера нет, а после падения полная
 * перезагрузка страницы — не недостаток, а ровно то, что нужно.
 */
export function AppErrorScreen({ error, onRetry }) {
  const { t } = useTranslation()

  return (
    <div
      role="alert"
      className="flex min-h-svh flex-col items-center justify-center gap-4 bg-gray-50 px-4 py-10 text-center dark:bg-gray-900"
    >
      <TriangleAlert className="size-10 text-red-500" aria-hidden="true" />
      <h1 className="text-xl font-semibold text-gray-900 dark:text-gray-100">{t('errorBoundary.title')}</h1>
      <p className="max-w-md text-sm text-gray-600 dark:text-gray-400">{t('errorBoundary.message')}</p>
      <div className="flex flex-wrap items-center justify-center gap-2">
        {onRetry && (
          <button type="button" onClick={onRetry} className={primaryButtonClass}>
            {t('errorBoundary.retry')}
          </button>
        )}
        <button
          type="button"
          onClick={() => window.location.reload()}
          className={onRetry ? secondaryButtonClass : primaryButtonClass}
        >
          {t('errorBoundary.reload')}
        </button>
        <a href="/projects" className={secondaryButtonClass}>
          {t('errorBoundary.goHome')}
        </a>
      </div>
      <ErrorDetails error={error} />
    </div>
  )
}

/**
 * Плашка на месте упавшего куска страницы — для секционных границ. Заголовок задаёт
 * вызывающий: «доска не отрисовалась» и «редактор не запустился» требуют разных
 * следующих шагов, и общее «что-то пошло не так» тут было бы отпиской.
 */
export function SectionErrorNotice({ error, reset, title, hint }) {
  const { t } = useTranslation()

  return (
    <div
      role="alert"
      className="rounded-md border border-red-200 bg-red-50 p-4 text-sm dark:border-red-900 dark:bg-red-950/40"
    >
      <p className="font-medium text-red-800 dark:text-red-300">{title ?? t('errorBoundary.sectionTitle')}</p>
      {hint && <p className="mt-1 text-red-700 dark:text-red-400">{hint}</p>}
      <button
        type="button"
        onClick={reset}
        className={`${secondaryButtonClass} mt-3 inline-flex items-center gap-2`}
      >
        <RotateCw className="size-4" aria-hidden="true" />
        {t('errorBoundary.retry')}
      </button>
      <div className="mt-3">
        <ErrorDetails error={error} />
      </div>
    </div>
  )
}
