import { Bell, BellOff, BellRing } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import {
  useProjectNotificationMode,
  useSetProjectNotificationMode,
} from '../../api/projectNotificationsQueries'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { inputClass } from '../ui/FormKit'

const MODES = ['ALL', 'PARTICIPATING', 'MUTED']

const MODE_ICONS = {
  ALL: BellRing,
  PARTICIPATING: Bell,
  MUTED: BellOff,
}

/**
 * «Следить за проектом / отписаться» (4.16) — рядом со звездой и по той же логике: личная
 * отметка участника, не меняющая проект.
 *
 * Список, а не тумблер «слежу / не слежу», потому что состояний три (сверх своего, своё,
 * ничего), и разница между ними — это как раз то, ради чего сюда приходят. Под выбором
 * стоит строчка, объясняющая последствие: «уведомления по проекту» — не та настройка,
 * результат которой видно сразу, ошибку в ней замечают через неделю по неприходящим
 * письмам, и подсказка здесь дешевле этой недели.
 */
export function ProjectNotificationsCard({ projectId }) {
  const { t } = useTranslation()
  const { data } = useProjectNotificationMode(projectId)
  const setMode = useSetProjectNotificationMode(projectId)

  if (!data) {
    return null
  }

  const Icon = MODE_ICONS[data.mode] ?? Bell

  return (
    <div className="rounded-lg border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-800">
      <label
        htmlFor="project-notification-mode"
        className="flex items-center gap-2 text-sm font-semibold text-gray-900 dark:text-gray-100"
      >
        <Icon className="h-4 w-4 shrink-0" aria-hidden="true" />
        {t('projectNotifications.title')}
      </label>
      <select
        id="project-notification-mode"
        value={data.mode}
        onChange={(event) => setMode.mutate(event.target.value)}
        disabled={setMode.isPending}
        className={`${inputClass} mt-3`}
      >
        {MODES.map((mode) => (
          <option key={mode} value={mode}>
            {t(`projectNotifications.modes.${mode}`)}
          </option>
        ))}
      </select>
      <p className="mt-2 text-xs text-gray-500 dark:text-gray-400">
        {t(`projectNotifications.modeHints.${data.mode}`)}
      </p>
      {setMode.isError && (
        <p className="mt-2 text-xs text-red-600 dark:text-red-400">
          {getLocalizedErrorMessage(setMode.error, t)}
        </p>
      )}
    </div>
  )
}
