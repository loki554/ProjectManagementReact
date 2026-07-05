import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { useProjectActivity } from '../../api/activityQueries'
import { TASK_NUMBER_BADGE_CLASS } from '../../lib/constants'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { useAuthenticatedImage } from '../../lib/useAuthenticatedImage'

// Аватар — отдельный компонент, т.к. useAuthenticatedImage — хук и в map по событиям
// его звать нельзя (тот же приём, что MemberAvatar на overview).
function ActorAvatar({ actor }) {
  const avatarUrl = useAuthenticatedImage(actor?.avatarUrl)
  return (
    <div className="flex h-8 w-8 shrink-0 items-center justify-center overflow-hidden rounded-full bg-gray-200 text-[10px] font-medium text-gray-600">
      {avatarUrl ? (
        <img src={avatarUrl} alt="" className="h-full w-full object-cover" />
      ) : (
        <span aria-hidden="true">
          {actor ? (actor.lastName?.[0] ?? '') + (actor.firstName?.[0] ?? '') : '?'}
        </span>
      )}
    </div>
  )
}

// Событие → параметры интерполяции для t('activity.<type>'). Коды (статусы, срочность,
// роли, названия полей проекта) переводятся здесь; имена/названия приходят готовыми
// строками-снапшотами из payload.
function buildMessageParams(item, t, formatDate) {
  const p = item.payload ?? {}
  const none = t('activity.none')
  // База — payload как есть (title/taskNumber/имена нужны почти всем шаблонам),
  // поверх — только переопределения кодов на переводы.
  switch (item.type) {
    case 'task_status_changed':
      return { ...p, old: t(`tasks.status.${p.old}`), new: t(`tasks.status.${p.new}`) }
    case 'task_urgency_changed':
      return { ...p, old: t(`urgency.${p.old}`), new: t(`urgency.${p.new}`) }
    case 'task_assignee_changed':
    case 'task_tag_changed':
      return { ...p, old: p.old ?? none, new: p.new ?? none }
    case 'task_due_date_changed':
      return {
        ...p,
        old: p.old ? formatDate(p.old) : none,
        new: p.new ? formatDate(p.new) : none,
      }
    case 'member_added':
      return { ...p, role: t(`roles.${p.role}`) }
    case 'member_role_changed':
      return { ...p, oldRole: t(`roles.${p.oldRole}`), newRole: t(`roles.${p.newRole}`) }
    case 'project_updated':
      return {
        fields: (p.changedFields ?? []).map((f) => t(`activity.fields.${f}`)).join(', '),
      }
    default:
      // task_created / task_deleted / task_title_changed / member_removed /
      // time_logged / attachment_added / wiki_updated — payload подставляется как есть.
      return { ...p }
  }
}

function FeedItem({ item, projectSlug }) {
  const { t, i18n } = useTranslation()

  const formatDate = (iso) =>
    new Date(iso).toLocaleString(i18n.language, { dateStyle: 'short', timeStyle: 'short' })

  // До недели — относительное время (локализуется браузером), дальше — обычная дата.
  function formatWhen(iso) {
    const date = new Date(iso)
    const diffMs = Date.now() - date.getTime()
    const minutes = Math.round(diffMs / 60000)
    if (minutes < 1) return t('activity.justNow')
    const rtf = new Intl.RelativeTimeFormat(i18n.language, { numeric: 'auto' })
    if (minutes < 60) return rtf.format(-minutes, 'minute')
    const hours = Math.round(minutes / 60)
    if (hours < 24) return rtf.format(-hours, 'hour')
    const days = Math.round(hours / 24)
    if (days < 7) return rtf.format(-days, 'day')
    return formatDate(iso)
  }

  const actorName = item.actor
    ? `${item.actor.lastName} ${item.actor.firstName}`
    : t('activity.deletedUser')
  const message = t(`activity.${item.type}`, {
    ...buildMessageParams(item, t, formatDate),
    defaultValue: item.type,
  })
  const taskNumber = item.payload?.taskNumber

  return (
    <li className="flex gap-3 py-3">
      <ActorAvatar actor={item.actor} />
      <div className="min-w-0 flex-1">
        <p className="text-sm text-gray-700">
          <span className="font-medium text-gray-900">{actorName}</span> {message}
          {taskNumber != null && (
            <>
              {' '}
              {item.taskId ? (
                <Link
                  to={`/projects/${projectSlug}/tasks/${taskNumber}`}
                  className={`${TASK_NUMBER_BADGE_CLASS} hover:underline`}
                >
                  #{taskNumber}
                </Link>
              ) : (
                // Задача уже удалена (taskId обнулён) — номер остаётся текстом без ссылки.
                <span className={TASK_NUMBER_BADGE_CLASS}>#{taskNumber}</span>
              )}
            </>
          )}
        </p>
        <p className="mt-0.5 text-xs text-gray-400" title={formatDate(item.createdAt)}>
          {formatWhen(item.createdAt)}
        </p>
      </div>
    </li>
  )
}

export function ActivityFeed({ projectId, projectSlug }) {
  const { t } = useTranslation()
  const { data, isLoading, isError, error, hasNextPage, fetchNextPage, isFetchingNextPage } =
    useProjectActivity(projectId)

  const items = data?.pages.flatMap((page) => page.items) ?? []

  return (
    <div className="rounded-lg border border-gray-200 bg-white p-6">
      <h2 className="text-sm font-semibold text-gray-900">{t('activity.title')}</h2>

      {isLoading && <p className="mt-3 text-sm text-gray-500">{t('activity.loading')}</p>}
      {isError && (
        <p className="mt-3 text-sm text-red-600">{getLocalizedErrorMessage(error, t)}</p>
      )}

      {!isLoading && !isError && items.length === 0 && (
        <p className="mt-3 text-sm text-gray-400">{t('activity.empty')}</p>
      )}

      {items.length > 0 && (
        <ul className="mt-2 divide-y divide-gray-100">
          {items.map((item) => (
            <FeedItem key={item.id} item={item} projectSlug={projectSlug} />
          ))}
        </ul>
      )}

      {hasNextPage && (
        <button
          type="button"
          onClick={() => fetchNextPage()}
          disabled={isFetchingNextPage}
          className="mt-2 text-sm font-medium text-purple-600 hover:underline disabled:cursor-not-allowed disabled:opacity-60"
        >
          {isFetchingNextPage ? t('activity.loadingMore') : t('activity.showMore')}
        </button>
      )}
    </div>
  )
}
