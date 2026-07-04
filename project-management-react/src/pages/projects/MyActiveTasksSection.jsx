import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMyActiveTasks } from '../../api/myTasksQueries'
import { ActiveTaskCard } from '../../components/tasks/ActiveTaskCard'
import { Pagination } from '../../components/ui/Pagination'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'

export function MyActiveTasksSection() {
  const { t } = useTranslation()
  const [page, setPage] = useState(0)
  const { data, isLoading, isError, error } = useMyActiveTasks(page)

  return (
    <div>
      {isLoading && <p className="text-gray-500">{t('home.myActiveTasks.loading')}</p>}
      {isError && <p className="text-sm text-red-600">{getLocalizedErrorMessage(error, t)}</p>}

      {!isLoading && !isError && data.items.length === 0 && (
        <div className="flex flex-col items-center justify-center rounded-lg border border-dashed border-gray-300 bg-white py-16 text-center">
          <p className="text-gray-600">{t('home.myActiveTasks.empty')}</p>
        </div>
      )}

      {!isLoading && !isError && data.items.length > 0 && (
        <>
          <ul className="space-y-3">
            {data.items.map((task) => (
              <ActiveTaskCard key={task.taskId} task={task} />
            ))}
          </ul>
          <Pagination page={data.page} totalPages={data.totalPages} onPageChange={setPage} />
        </>
      )}
    </div>
  )
}
