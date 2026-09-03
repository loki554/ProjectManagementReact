import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useAcceptInvitation, useInvitation } from '../api/invitationsQueries'
import { primaryButtonClass, secondaryButtonClass } from '../components/ui/FormKit'
import { getLocalizedErrorMessage } from '../lib/errorMessage'
import { AuthLayout } from './auth/authFormKit'
import { useAuthStore } from '../stores/authStore'

/**
 * Страница по ссылке из письма-приглашения (4.2).
 *
 * Единственная страница приложения, которая осмысленно работает и для вошедшего, и для
 * анонимного посетителя, — потому что в этом и состоит задача: приглашение приходит тому,
 * у кого аккаунта, скорее всего, ещё нет. Отсюда три состояния:
 *
 *   вошёл и адрес совпал     — кнопка «принять», после неё сразу в проект;
 *   вошёл под другим адресом — сервер отдаст 403, и мы честно объясняем, чей это инвайт;
 *   не вошёл                 — две дороги: завести аккаунт (адрес подставится сам) или
 *                              войти в существующий и вернуться сюда же.
 *
 * Токен всё это время живёт только в query-параметре: ни в стор, ни в localStorage он не
 * кладётся. Ссылка одноразовая по смыслу, а не по хранению — держать её у себя после того,
 * как ею воспользовались, незачем.
 */
export function InvitePage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const token = searchParams.get('token')

  const currentUser = useAuthStore((state) => state.user)
  const isAuthenticated = Boolean(useAuthStore((state) => state.accessToken))

  const { data: invitation, isLoading, isError, error } = useInvitation(token)
  const acceptMutation = useAcceptInvitation(token)

  if (!token) {
    return (
      <AuthLayout title={t('invitation.title')}>
        <p className="text-gray-600 dark:text-gray-400">{t('invitation.missingToken')}</p>
        <BackToProjects />
      </AuthLayout>
    )
  }

  if (isLoading) {
    return (
      <AuthLayout title={t('invitation.title')}>
        <p className="text-gray-500 dark:text-gray-400">{t('invitation.loading')}</p>
      </AuthLayout>
    )
  }

  // Просроченное приглашение неотличимо от несуществующего и на бэкенде (оба INVALID_TOKEN),
  // и здесь: совет в обоих случаях один — попросить пригласить заново.
  if (isError) {
    return (
      <AuthLayout title={t('invitation.title')}>
        <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(error, t)}</p>
        <p className="mt-2 text-sm text-gray-600 dark:text-gray-400">{t('invitation.expiredHint')}</p>
        <BackToProjects />
      </AuthLayout>
    )
  }

  if (acceptMutation.isSuccess) {
    const accepted = acceptMutation.data
    return (
      <AuthLayout title={t('invitation.acceptedTitle')}>
        <p className="text-gray-600 dark:text-gray-400">
          {t('invitation.acceptedMessage', { project: accepted.projectName })}
        </p>
        <Link
          to={`/projects/${accepted.projectSlug}`}
          className="mt-4 inline-block text-purple-600 hover:underline dark:text-purple-400"
        >
          {t('invitation.goToProject')}
        </Link>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout title={t('invitation.title')}>
      <p className="text-gray-700 dark:text-gray-300">
        {t('invitation.intro', {
          inviter: invitation.invitedByName ?? t('invitation.someone'),
          project: invitation.projectName,
        })}
      </p>
      <dl className="mt-4 space-y-1 text-sm text-gray-600 dark:text-gray-400">
        <div className="flex justify-between gap-4">
          <dt>{t('invitation.email')}</dt>
          <dd className="font-medium text-gray-900 dark:text-gray-100">{invitation.email}</dd>
        </div>
        <div className="flex justify-between gap-4">
          <dt>{t('invitation.role')}</dt>
          <dd className="font-medium text-gray-900 dark:text-gray-100">{t(`roles.${invitation.role}`)}</dd>
        </div>
      </dl>

      {acceptMutation.isError && (
        <p className="mt-4 text-sm text-red-600 dark:text-red-400">
          {getLocalizedErrorMessage(acceptMutation.error, t)}
        </p>
      )}

      {isAuthenticated ? (
        <div className="mt-6 space-y-3">
          <button
            type="button"
            onClick={() => acceptMutation.mutate()}
            disabled={acceptMutation.isPending}
            className={`w-full ${primaryButtonClass}`}
          >
            {acceptMutation.isPending ? t('invitation.accepting') : t('invitation.accept')}
          </button>
          {/* Адрес, под которым человек вошёл, показываем всегда, а не только после отказа:
              «принять» под чужим аккаунтом — самая частая ошибка на этой странице, и
              заметить её лучше до нажатия, чем после 403. */}
          {currentUser?.email && (
            <p className="text-xs text-gray-500 dark:text-gray-400">
              {t('invitation.signedInAs', { email: currentUser.email })}{' '}
              <button
                type="button"
                onClick={() => navigate(`/login?invite=${encodeURIComponent(token)}`)}
                className="text-purple-600 hover:underline dark:text-purple-400"
              >
                {t('invitation.switchAccount')}
              </button>
            </p>
          )}
        </div>
      ) : (
        <div className="mt-6 space-y-3">
          <Link
            to={`/register?invite=${encodeURIComponent(token)}`}
            className={`block w-full text-center ${primaryButtonClass}`}
          >
            {t('invitation.createAccount')}
          </Link>
          <Link
            to={`/login?invite=${encodeURIComponent(token)}`}
            className={`block w-full text-center ${secondaryButtonClass}`}
          >
            {t('invitation.signIn')}
          </Link>
        </div>
      )}
    </AuthLayout>
  )
}

function BackToProjects() {
  const { t } = useTranslation()
  return (
    <Link to="/projects" className="mt-4 inline-block text-purple-600 hover:underline dark:text-purple-400">
      {t('invitation.backToProjects')}
    </Link>
  )
}
