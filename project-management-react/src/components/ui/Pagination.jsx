import { useTranslation } from 'react-i18next'
import { secondaryButtonClass } from './FormKit'

// Компонент намеренно "глупый" (не знает про react-query/фильтры) — переиспользуем для
// любого будущего постранично выдаваемого списка, передав только текущую страницу и обработчик.
export function Pagination({ page, totalPages, onPageChange }) {
  const { t } = useTranslation()

  if (totalPages <= 1) {
    return null
  }

  return (
    <div className="mt-4 flex items-center justify-between">
      <button
        type="button"
        className={secondaryButtonClass}
        disabled={page <= 0}
        onClick={() => onPageChange(page - 1)}
      >
        {t('pagination.previous')}
      </button>
      <span className="text-sm text-gray-600 dark:text-gray-400">
        {t('pagination.pageInfo', { page: page + 1, total: totalPages })}
      </span>
      <button
        type="button"
        className={secondaryButtonClass}
        disabled={page >= totalPages - 1}
        onClick={() => onPageChange(page + 1)}
      >
        {t('pagination.next')}
      </button>
    </div>
  )
}
