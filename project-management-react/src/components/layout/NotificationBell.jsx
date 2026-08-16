import { Bell } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import {
  useMarkAllNotificationsRead,
  useMarkNotificationRead,
  useNotifications,
  useUnreadNotificationCount,
} from '../../api/notificationsQueries'
import { TASK_NUMBER_BADGE_CLASS } from '../../lib/constants'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'

// task_due_soon/task_overdue генерируются планировщиком, а не человеком — у них нет
// actor (см. NotificationResponse.actor), имя перед сообщением не показываем.
const SYSTEM_TYPES = new Set(['task_due_soon', 'task_overdue'])

// payload приходит готовым снапшотом (title/taskNumber/commentExcerpt) — коду переводить
// тут нечего, тот же приём, что buildMessageParams в ActivityFeed, но проще: ни один из
// 4 типов уведомлений не меняет одно значение на другое.
function buildMessage(item, t) {
  return t(`notifications.types.${item.type}`, { ...item.payload, defaultValue: item.type })
}

function formatWhen(iso, t, language) {
  const date = new Date(iso)
  const minutes = Math.round((Date.now() - date.getTime()) / 60000)
  if (minutes < 1) return t('activity.justNow')
  const rtf = new Intl.RelativeTimeFormat(language, { numeric: 'auto' })
  if (minutes < 60) return rtf.format(-minutes, 'minute')
  const hours = Math.round(minutes / 60)
  if (hours < 24) return rtf.format(-hours, 'hour')
  const days = Math.round(hours / 24)
  if (days < 7) return rtf.format(-days, 'day')
  return date.toLocaleString(language, { dateStyle: 'short', timeStyle: 'short' })
}

function NotificationItem({ item, onNavigate }) {
  const { t, i18n } = useTranslation()
  const markRead = useMarkNotificationRead()

  const actorName = item.actor ? `${item.actor.lastName} ${item.actor.firstName}` : null
  const taskNumber = item.payload?.taskNumber
  const projectSlug = item.payload?.projectSlug

  function handleClick() {
    if (!item.read) {
      markRead.mutate(item.id)
    }
    onNavigate()
  }

  const body = (
    <div className={`flex gap-2 px-4 py-2.5 text-sm ${!item.read ? 'bg-purple-50 dark:bg-purple-900/20' : ''}`}>
      <span
        aria-hidden="true"
        className={`mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full ${!item.read ? 'bg-purple-500' : 'bg-transparent'}`}
      />
      <div className="min-w-0 flex-1">
        <p className="text-gray-700 dark:text-gray-300">
          {!SYSTEM_TYPES.has(item.type) && actorName && (
            <span className="font-medium text-gray-900 dark:text-gray-100">{actorName} </span>
          )}
          {buildMessage(item, t)}
          {taskNumber != null && (
            <>
              {' '}
              <span className={TASK_NUMBER_BADGE_CLASS}>#{taskNumber}</span>
            </>
          )}
        </p>
        <p className="mt-0.5 text-xs text-gray-400 dark:text-gray-500">
          {formatWhen(item.createdAt, t, i18n.language)}
        </p>
      </div>
    </div>
  )

  // taskNumber/projectSlug всегда присутствуют на практике (уведомление удаляется
  // каскадом вместе с задачей, см. V16), но без ссылки элемент всё равно читаем —
  // тот же защитный приём, что ActivityFeed делает для удалённой задачи.
  if (taskNumber == null || !projectSlug) {
    return body
  }
  return (
    <Link
      to={`/projects/${projectSlug}/tasks/${taskNumber}`}
      onClick={handleClick}
      className="block hover:bg-gray-50 dark:hover:bg-gray-700"
    >
      {body}
    </Link>
  )
}

export function NotificationBell() {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const containerRef = useRef(null)

  const { data: unreadCount = 0 } = useUnreadNotificationCount()
  const { data, isLoading, isError, error } = useNotifications(open)
  const markAllRead = useMarkAllNotificationsRead()

  useEffect(() => {
    function handleClickOutside(event) {
      if (containerRef.current && !containerRef.current.contains(event.target)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  const items = data?.items ?? []

  return (
    <div className="relative" ref={containerRef}>
      <button
        type="button"
        onClick={() => setOpen((isOpen) => !isOpen)}
        aria-label={t('notifications.title')}
        title={t('notifications.title')}
        className="relative rounded p-1.5 text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-700"
      >
        <Bell className="h-4 w-4" />
        {unreadCount > 0 && (
          <span className="absolute -right-1 -top-1 flex h-4 min-w-[1rem] items-center justify-center rounded-full bg-red-500 px-1 text-[10px] font-semibold leading-none text-white">
            {unreadCount > 9 ? '9+' : unreadCount}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute right-0 z-10 mt-2 w-80 max-w-[calc(100vw-2rem)] rounded-md border border-gray-200 bg-white shadow-lg dark:border-gray-700 dark:bg-gray-800">
          <div className="flex items-center justify-between border-b border-gray-100 px-4 py-2 dark:border-gray-700">
            <span className="text-sm font-semibold text-gray-900 dark:text-gray-100">{t('notifications.title')}</span>
            {unreadCount > 0 && (
              <button
                type="button"
                onClick={() => markAllRead.mutate()}
                disabled={markAllRead.isPending}
                className="text-xs font-medium text-purple-600 hover:underline disabled:opacity-60 dark:text-purple-400"
              >
                {t('notifications.markAllRead')}
              </button>
            )}
          </div>

          <div className="max-h-96 overflow-y-auto">
            {isLoading && (
              <p className="px-4 py-3 text-sm text-gray-500 dark:text-gray-400">{t('notifications.loading')}</p>
            )}
            {isError && (
              <p className="px-4 py-3 text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(error, t)}</p>
            )}
            {!isLoading && !isError && items.length === 0 && (
              <p className="px-4 py-3 text-sm text-gray-400 dark:text-gray-500">{t('notifications.empty')}</p>
            )}
            {items.length > 0 && (
              <ul className="divide-y divide-gray-100 dark:divide-gray-700">
                {items.map((item) => (
                  <li key={item.id}>
                    <NotificationItem item={item} onNavigate={() => setOpen(false)} />
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
