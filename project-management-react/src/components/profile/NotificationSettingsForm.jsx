import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNotificationSettings, useUpdateNotificationSettings } from '../../api/notificationSettingsQueries'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { submitButtonClass } from '../ui/FormKit'

// Те же пять типов, что у колокольчика (NotificationBell) и у бэкенда
// (NotificationService.TYPE_*). Ключ поля совпадает с полем ответа API, поэтому список
// одновременно и порядок в форме, и способ собрать тело запроса.
// taskMention стоит сразу за taskComment: это соседние по смыслу вещи, и разница между
// ними («в треде пишут» против «меня позвали») читается только рядом.
const TYPE_FIELDS = ['taskAssigned', 'taskComment', 'taskMention', 'taskDueSoon', 'taskOverdue']

const MODES = ['INSTANT', 'DAILY_DIGEST']

/**
 * Настройки email-уведомлений в профиле (4.3).
 *
 * Форма локальная, а не «переключил — сохранилось»: у неё семь связанных полей, и
 * автосохранение каждого означало бы семь запросов на одно осмысленное изменение и
 * промежуточные состояния, которых человек не выбирал.
 *
 * Отдельная секция, а не поля в форме профиля выше, по той же причине, что и смена
 * пароля: это про другое, и путать «сохранить имя» с «перестать получать письма» не
 * стоит ни визуально, ни кнопкой.
 */
export function NotificationSettingsForm() {
  const { t } = useTranslation()
  const { data, isLoading, isError, error } = useNotificationSettings()
  const mutation = useUpdateNotificationSettings()

  // Черновик появляется, когда приехали настройки, и переинициализируется, если они
  // изменились в кэше (сохранение кладёт туда ответ сервера).
  const [draft, setDraft] = useState(null)
  useEffect(() => {
    if (data) {
      setDraft(data)
    }
  }, [data])

  if (isLoading || !draft) {
    return (
      <Section>
        <p className="text-sm text-gray-500 dark:text-gray-400">{t('profile.notifications.loading')}</p>
      </Section>
    )
  }

  if (isError) {
    return (
      <Section>
        <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(error, t)}</p>
      </Section>
    )
  }

  const isDirty = TYPE_FIELDS.concat(['emailEnabled', 'mode']).some((key) => draft[key] !== data[key])

  function set(key, value) {
    setDraft((current) => ({ ...current, [key]: value }))
  }

  return (
    <Section>
      <form
        onSubmit={(event) => {
          event.preventDefault()
          mutation.mutate(draft)
        }}
        className="space-y-4"
      >
        <label className="flex items-start gap-3">
          <input
            type="checkbox"
            className="mt-0.5 h-4 w-4 rounded border-gray-300 text-purple-600 focus:ring-purple-500 dark:border-gray-600"
            checked={draft.emailEnabled}
            onChange={(event) => set('emailEnabled', event.target.checked)}
          />
          <span>
            <span className="block text-sm font-medium text-gray-900 dark:text-gray-100">
              {t('profile.notifications.emailEnabled')}
            </span>
            <span className="block text-xs text-gray-500 dark:text-gray-400">
              {t('profile.notifications.emailEnabledHint')}
            </span>
          </span>
        </label>

        {/* Режим и типы гасим, а не прячем, когда почта выключена целиком: спрятанные
            настройки выглядят как потерянные, а человек должен видеть, к чему вернётся,
            если включит письма обратно. */}
        <fieldset disabled={!draft.emailEnabled} className="space-y-4 disabled:opacity-50">
          <div>
            <span className="mb-2 block text-sm font-medium text-gray-700 dark:text-gray-300">
              {t('profile.notifications.mode')}
            </span>
            <div className="space-y-2">
              {MODES.map((mode) => (
                <label key={mode} className="flex items-start gap-3">
                  <input
                    type="radio"
                    name="notification-mode"
                    className="mt-0.5 h-4 w-4 border-gray-300 text-purple-600 focus:ring-purple-500 dark:border-gray-600"
                    checked={draft.mode === mode}
                    onChange={() => set('mode', mode)}
                  />
                  <span>
                    <span className="block text-sm text-gray-900 dark:text-gray-100">
                      {t(`profile.notifications.modes.${mode}`)}
                    </span>
                    <span className="block text-xs text-gray-500 dark:text-gray-400">
                      {t(`profile.notifications.modeHints.${mode}`)}
                    </span>
                  </span>
                </label>
              ))}
            </div>
          </div>

          <div>
            <span className="mb-2 block text-sm font-medium text-gray-700 dark:text-gray-300">
              {t('profile.notifications.types')}
            </span>
            <div className="space-y-2">
              {TYPE_FIELDS.map((field) => (
                <label key={field} className="flex items-center gap-3">
                  <input
                    type="checkbox"
                    className="h-4 w-4 rounded border-gray-300 text-purple-600 focus:ring-purple-500 dark:border-gray-600"
                    checked={draft[field]}
                    onChange={(event) => set(field, event.target.checked)}
                  />
                  <span className="text-sm text-gray-900 dark:text-gray-100">
                    {t(`profile.notifications.typeLabels.${field}`)}
                  </span>
                </label>
              ))}
            </div>
            {/* Про колокольчик сказать надо: без этой строчки «выключил всё» читается как
                «перестану узнавать о задачах вообще», а выключается только почта. */}
            <p className="mt-2 text-xs text-gray-500 dark:text-gray-400">
              {t('profile.notifications.bellStaysHint')}
            </p>
          </div>
        </fieldset>

        {mutation.isError && (
          <p className="text-sm text-red-600 dark:text-red-400">
            {getLocalizedErrorMessage(mutation.error, t)}
          </p>
        )}
        {mutation.isSuccess && !isDirty && (
          <p className="text-sm text-green-700 dark:text-green-400">{t('profile.saved')}</p>
        )}

        <button type="submit" disabled={mutation.isPending || !isDirty} className={submitButtonClass}>
          {mutation.isPending ? t('profile.saving') : t('profile.save')}
        </button>
      </form>
    </Section>
  )
}

function Section({ children }) {
  const { t } = useTranslation()
  return (
    <section className="mt-10 border-t border-gray-200 pt-8 dark:border-gray-700">
      <h2 className="mb-1 text-lg font-semibold text-gray-900 dark:text-gray-100">
        {t('profile.notifications.title')}
      </h2>
      <p className="mb-4 text-sm text-gray-600 dark:text-gray-400">{t('profile.notifications.hint')}</p>
      {children}
    </section>
  )
}
