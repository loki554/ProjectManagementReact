import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { taskStatusBadgeClass, taskUrgencyBadgeClass } from '../../lib/constants'
import { tagBadgeStyle } from '../../lib/tagColor'

export function ActiveTaskCard({ task }) {
  const { t, i18n } = useTranslation()

  return (
    <li className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm">
      <Link
        to={`/projects/${task.projectId}/tasks/${task.taskId}`}
        className="flex flex-wrap items-center justify-between gap-2"
      >
        <div className="min-w-0">
          <p className="truncate text-xs text-gray-500">{task.projectName}</p>
          <p className="truncate font-medium text-gray-900">{task.title}</p>
        </div>

        <div className="flex flex-wrap items-center gap-2 text-xs">
          <span className={`rounded-full px-2 py-0.5 font-medium ${taskStatusBadgeClass(task.status)}`}>
            {t(`tasks.status.${task.status}`)}
          </span>
          <span className={`rounded-full px-2 py-0.5 font-medium ${taskUrgencyBadgeClass(task.urgency)}`}>
            {t(`urgency.${task.urgency}`)}
          </span>
          {task.dueDate && (
            <span className="text-gray-500">{t(`tasks.detail.dueDateUntil`)} {new Date(task.dueDate).toLocaleDateString(i18n.language)}</span>
          )}
          {task.tag && (
            <span className="rounded-full px-2 py-0.5 font-medium" style={tagBadgeStyle(task.tag.color)}>
              {task.tag.name}
            </span>
          )}
          <span className="text-gray-500">
            {Number(task.totalHoursSpent).toFixed(2)} {t('tasks.timeLogs.hoursShort')}
          </span>
        </div>
      </Link>
    </li>
  )
}
