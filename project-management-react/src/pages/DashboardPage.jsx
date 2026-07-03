import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { logout } from '../api/authApi'
import { secondaryButtonClass } from '../components/ui/FormKit'
import { useAuthenticatedImage } from '../lib/useAuthenticatedImage'
import { useAuthStore } from '../stores/authStore'

// Временная заглушка вместо реального дашборда со списком проектов (Phase 3).
// Задача сейчас — подтвердить, что вся цепочка логина/сессии работает end-to-end.
export function DashboardPage() {
  const { t } = useTranslation()
  const user = useAuthStore((state) => state.user)
  const refreshToken = useAuthStore((state) => state.refreshToken)
  const clearSession = useAuthStore((state) => state.clearSession)
  const avatarObjectUrl = useAuthenticatedImage(user?.avatarUrl)

  const logoutMutation = useMutation({
    mutationFn: () => logout(refreshToken),
    onSettled: () => clearSession(),
  })

  return (
    <div className="mx-auto max-w-2xl px-4 py-12">
      <div className="flex items-center gap-4">
        <div className="h-14 w-14 shrink-0 overflow-hidden rounded-full bg-gray-200">
          {avatarObjectUrl && (
            <img src={avatarObjectUrl} alt="" className="h-full w-full object-cover" />
          )}
        </div>
        <div>
          <h1 className="text-2xl font-semibold text-gray-900">
            {t('dashboard.welcome', { name: user?.firstName ?? '' })}
          </h1>
          <p className="mt-1 text-gray-600">
            {user?.lastName} {user?.firstName} {user?.patronymic} · {user?.email}
          </p>
        </div>
      </div>

      <div className="mt-6 flex gap-3">
        <Link to="/profile" className={secondaryButtonClass}>
          {t('dashboard.profileLink')}
        </Link>
        <button
          type="button"
          onClick={() => logoutMutation.mutate()}
          disabled={logoutMutation.isPending}
          className={secondaryButtonClass}
        >
          {logoutMutation.isPending ? t('dashboard.loggingOut') : t('dashboard.logout')}
        </button>
      </div>
    </div>
  )
}
