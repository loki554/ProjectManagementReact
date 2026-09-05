import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router-dom'
import { useUnsubscribe } from '../api/notificationSettingsQueries'
import { primaryButtonClass } from '../components/ui/FormKit'
import { getLocalizedErrorMessage } from '../lib/errorMessage'
import { AuthLayout } from './auth/authFormKit'

/**
 * Страница по ссылке «отписаться» из письма-уведомления (4.3).
 *
 * Ссылка ведёт сюда, а не сразу в API, и это не лишний шаг, а единственный способ не
 * отписывать человека против его воли: по ссылкам из писем ходят сами — почтовые
 * антивирусы, превьюшники мессенджеров, кэш почтовых клиентов. Такой обход открывает
 * страницу, и на этом всё заканчивается: отписка происходит по нажатию кнопки (POST),
 * а не по факту открытия.
 *
 * Работает и без входа — как InvitePage и по той же причине: письмо приходит и тому, кто
 * пароля от аккаунта уже не помнит. Отписка, ради которой надо вспомнить пароль, на
 * практике заменяется кнопкой «спам», а это дороже для всех.
 *
 * Чей это адрес, страница не показывает: ей это неоткуда узнать (токен — подпись, а не
 * ключ доступа к профилю), и сообщать по ссылке из старого письма, жив ли ещё аккаунт,
 * незачем.
 */
export function UnsubscribePage() {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  const token = searchParams.get('token')
  const mutation = useUnsubscribe(token)

  if (!token) {
    return (
      <AuthLayout title={t('unsubscribe.title')}>
        <p className="text-gray-600 dark:text-gray-400">{t('unsubscribe.missingToken')}</p>
        <BackLink />
      </AuthLayout>
    )
  }

  if (mutation.isSuccess) {
    return (
      <AuthLayout title={t('unsubscribe.doneTitle')}>
        <p className="text-gray-600 dark:text-gray-400">{t('unsubscribe.doneMessage')}</p>
        {/* Отписка гасит только письма, поэтому сразу показываем дорогу назад: вернуть их
            и настроить точнее можно в профиле, и знать об этом человек должен здесь. */}
        <Link
          to="/profile"
          className="mt-4 inline-block text-purple-600 hover:underline dark:text-purple-400"
        >
          {t('unsubscribe.goToSettings')}
        </Link>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout title={t('unsubscribe.title')}>
      <p className="text-gray-700 dark:text-gray-300">{t('unsubscribe.intro')}</p>
      <p className="mt-2 text-sm text-gray-500 dark:text-gray-400">{t('unsubscribe.bellStaysHint')}</p>

      {mutation.isError && (
        <p className="mt-4 text-sm text-red-600 dark:text-red-400">
          {getLocalizedErrorMessage(mutation.error, t)}
        </p>
      )}

      <button
        type="button"
        onClick={() => mutation.mutate()}
        disabled={mutation.isPending}
        className={`mt-6 w-full ${primaryButtonClass}`}
      >
        {mutation.isPending ? t('unsubscribe.submitting') : t('unsubscribe.submit')}
      </button>
      <BackLink />
    </AuthLayout>
  )
}

function BackLink() {
  const { t } = useTranslation()
  return (
    <Link to="/projects" className="mt-4 inline-block text-purple-600 hover:underline dark:text-purple-400">
      {t('unsubscribe.backToProjects')}
    </Link>
  )
}
