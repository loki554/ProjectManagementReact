import { Undo2 } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useParams } from 'react-router-dom'
import { useProjectBySlug, useProjectMembers } from '../../api/projectsQueries'
import { useRestoreTask, useTrash } from '../../api/tasksQueries'
import { secondaryButtonClass } from '../../components/ui/FormKit'
import {
  TASK_NUMBER_BADGE_CLASS,
  roleIsAtLeast,
  taskStatusBadgeClass,
} from '../../lib/constants'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { formatDueDate } from '../../lib/taskDisplay'
import { useAuthStore } from '../../stores/authStore'

const cellClass = 'px-3 py-2 align-middle'

export function ProjectTrashPage() {
  const { t, i18n } = useTranslation()
  const { projectSlug } = useParams()
  const currentUser = useAuthStore((state) => state.user)

  const { data: project } = useProjectBySlug(projectSlug)
  const projectId = project?.id
  const { data: members } = useProjectMembers(projectId)
  const { data: trashedTasks, isLoading, isError, error } = useTrash(projectId)
  const restoreTask = useRestoreTask(projectId)

  // Косметическое скрытие: сервер всё равно требует MEMBER и выше на восстановление,
  // как и на удаление.
  const myMembership = members?.find((member) => member.userId === currentUser?.id)
  const canRestore = myMembership ? roleIsAtLeast(myMembership.role, 'MEMBER') : false

  return (
    <div className="flex h-full flex-col gap-3 px-4 py-4">
      <div>
        <h1 className="text-lg font-semibold text-gray-900 dark:text-gray-100">{t('trash.title')}</h1>
        <p className="text-sm text-gray-500 dark:text-gray-400">{t('trash.description')}</p>
      </div>

      {isLoading && <p className="text-gray-500 dark:text-gray-400">{t('tasks.loading')}</p>}
      {isError && <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(error, t)}</p>}
      {restoreTask.isError && (
        <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(restoreTask.error, t)}</p>
      )}

      {!isLoading && !isError && trashedTasks?.length === 0 && (
        <p className="text-sm text-gray-400 dark:text-gray-500">{t('trash.empty')}</p>
      )}

      {!isLoading && !isError && trashedTasks?.length > 0 && (
        <div className="min-h-0 flex-1 overflow-auto rounded-lg border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
          <table className="w-full min-w-200 table-fixed text-sm">
            <colgroup>
              <col className="w-14" />
              <col />
              <col className="w-32" />
              <col className="w-44" />
              <col className="w-44" />
              <col className="w-32" />
            </colgroup>
            <thead className="sticky top-0 z-10">
              <tr className="border-b border-gray-200 bg-gray-50 text-left text-xs font-semibold tracking-wide text-gray-500 uppercase dark:border-gray-700 dark:bg-gray-900 dark:text-gray-400">
                <th scope="col" className="px-3 py-2">
                  №
                </th>
                <th scope="col" className="px-3 py-2">
                  {t('tasks.detail.titleLabel')}
                </th>
                <th scope="col" className="px-3 py-2">
                  {t('tasks.detail.statusLabel')}
                </th>
                <th scope="col" className="px-3 py-2">
                  {t('trash.deletedAt')}
                </th>
                <th scope="col" className="px-3 py-2">
                  {t('trash.purgeAfter')}
                </th>
                <th scope="col" className="px-3 py-2" />
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 dark:divide-gray-700/60">
              {trashedTasks.map((task) => (
                <tr key={task.id} className="even:bg-gray-50/70 dark:even:bg-gray-900/30">
                  <td className={cellClass}>
                    <span className={TASK_NUMBER_BADGE_CLASS}>#{task.taskNumber}</span>
                  </td>
                  <td className={cellClass}>
                    <span title={task.title} className="line-clamp-2 font-medium text-gray-900 dark:text-gray-100">
                      {task.title}
                    </span>
                    {task.subtaskCount > 0 && (
                      <span className="block text-xs text-gray-500 dark:text-gray-400">
                        {t('trash.withSubtasks', { count: task.subtaskCount })}
                      </span>
                    )}
                  </td>
                  <td className={cellClass}>
                    <span
                      className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap ${taskStatusBadgeClass(task.status)}`}
                    >
                      {t(`tasks.status.${task.status}`)}
                    </span>
                  </td>
                  <td className={`${cellClass} text-gray-600 dark:text-gray-400`}>
                    {formatDueDate(task.deletedAt, i18n.language)}
                  </td>
                  <td className={`${cellClass} text-gray-600 dark:text-gray-400`}>
                    {formatDueDate(task.purgeAfter, i18n.language)}
                  </td>
                  <td className={`${cellClass} text-right`}>
                    {canRestore && (
                      <button
                        type="button"
                        className={`${secondaryButtonClass} inline-flex items-center gap-1 whitespace-nowrap`}
                        disabled={restoreTask.isPending}
                        onClick={() => restoreTask.mutate(task.id)}
                      >
                        <Undo2 className="h-4 w-4" aria-hidden="true" />
                        {t('trash.restore')}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
