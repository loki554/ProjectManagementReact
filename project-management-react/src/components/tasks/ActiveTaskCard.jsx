import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { TASK_NUMBER_BADGE_CLASS, taskStatusBadgeClass, taskUrgencyBadgeClass } from '../../lib/constants'
import { tagBadgeStyle } from '../../lib/tagColor'
import { msUntil } from '../../lib/timeUntil'

const HOUR_MS = 60 * 60 * 1000
const DAY_MS = 24 * HOUR_MS
// Предупреждение о приближающемся сроке показываем в последние 3 дня до дедлайна включительно
// (и для уже просроченных задач — они как минимум так же горящие); более ранние сроки не выделяем.
const DUE_SOON_THRESHOLD_MS = 3 * DAY_MS

export function ActiveTaskCard({ task }) {
  const { t, i18n } = useTranslation()

  const msLeft = task.dueDate ? msUntil(task.dueDate) : null
  const isDueSoon = msLeft !== null && msLeft <= DUE_SOON_THRESHOLD_MS
  const isOverdue = isDueSoon && msLeft <= 0
  const isLessThanADay = isDueSoon && !isOverdue && msLeft < DAY_MS

  return (
    <li
      className={`rounded-lg border border-gray-200 bg-white p-4 shadow-sm ${
        isDueSoon ? 'border-r-4 border-r-red-500' : ''
      }`}
    >
      <Link to={`/projects/${task.projectId}/tasks/${task.taskId}`} className="block">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="min-w-0">
            <p className="truncate text-xs text-gray-500">{task.projectName}</p>
            <p className="flex items-center gap-2 font-medium text-gray-900">
              <span className={TASK_NUMBER_BADGE_CLASS}>#{task.taskNumber}</span>
              <span className="min-w-0 truncate">{task.title}</span>
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-2 text-xs">
            <span className={`rounded-full px-2 py-0.5 font-medium ${taskStatusBadgeClass(task.status)}`}>
              {t(`tasks.status.${task.status}`)}
            </span>
            <span className={`rounded-full px-2 py-0.5 font-medium ${taskUrgencyBadgeClass(task.urgency)}`}>
              {t(`urgency.${task.urgency}`)}
            </span>
            {task.dueDate && (
              <span className="text-gray-500">
                {t(`tasks.detail.dueDateUntil`)}{' '}
                {new Date(task.dueDate).toLocaleString(i18n.language, { dateStyle: 'short', timeStyle: 'short' })}
              </span>
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
        </div>

        {isDueSoon && (
          <p className="mt-2 text-sm font-medium text-red-600">
            {isOverdue
              ? t('home.myActiveTasks.overdue')
              : isLessThanADay
                ? t('home.myActiveTasks.dueSoonHours', { count: Math.ceil(msLeft / HOUR_MS) })
                : t('home.myActiveTasks.dueSoon', { count: Math.ceil(msLeft / DAY_MS) })}
          </p>
        )}
      </Link>
    </li>
  )
}
