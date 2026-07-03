import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { logout } from '../api/authApi'
import { useAuthStore } from '../stores/authStore'

// Временная заглушка вместо реального дашборда со списком проектов (Phase 3).
// Задача сейчас — подтвердить, что вся цепочка логина/сессии работает end-to-end.
export function DashboardPage() {
  const { t } = useTranslation()
  const user = useAuthStore((state) => state.user)
  const refreshToken = useAuthStore((state) => state.refreshToken)
  const clearSession = useAuthStore((state) => state.clearSession)

  const logoutMutation = useMutation({
    mutationFn: () => logout(refreshToken),
    onSettled: () => clearSession(),
  })

  return (
    <div className="mx-auto max-w-2xl px-4 py-12">
      <h1 className="text-2xl font-semibold text-gray-900">
        {t('dashboard.welcome', { name: user?.firstName ?? '' })}
      </h1>
      <p className="mt-2 text-gray-600">
        {user?.lastName} {user?.firstName} {user?.patronymic} · {user?.email}
      </p>

      <button
        type="button"
        onClick={() => logoutMutation.mutate()}
        disabled={logoutMutation.isPending}
        className="mt-6 rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-60"
      >
        {logoutMutation.isPending ? t('dashboard.loggingOut') : t('dashboard.logout')}
      </button>
    </div>
  )
}
