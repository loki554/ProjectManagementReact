import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router-dom'
import { verifyEmail } from '../../api/authApi'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { AuthLayout } from './authFormKit'

export function VerifyEmailPage() {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  const token = searchParams.get('token')

  // useQuery, а не useMutation: подтверждение должно запускаться само при заходе
  // по ссылке из письма ("fetch on mount"), а не по клику пользователя. useQuery
  // спроектирован именно под это и сам дедуплицирует повторный вызов эффекта
  // в React StrictMode (dev) по queryKey — без него useMutation, вызванный вручную
  // из useEffect, не подхватывал состояние после реального успешного запроса.
  const query = useQuery({
    queryKey: ['verify-email', token],
    queryFn: () => verifyEmail(token),
    enabled: Boolean(token),
    retry: false,
    staleTime: Infinity,
  })

  if (!token) {
    return (
      <AuthLayout title={t('auth.verifyEmail.title')}>
        <p className="text-red-600">{t('auth.verifyEmail.missingToken')}</p>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout title={t('auth.verifyEmail.title')}>
      {query.isPending && <p className="text-gray-600">{t('auth.verifyEmail.pending')}</p>}

      {query.isSuccess && (
        <>
          <p className="text-green-700">{t('auth.verifyEmail.success')}</p>
          <Link to="/login" className="mt-4 inline-block text-purple-600 hover:underline">
            {t('auth.verifyEmail.goToLogin')}
          </Link>
        </>
      )}

      {query.isError && (
        <p className="text-red-600">{getLocalizedErrorMessage(query.error, t)}</p>
      )}
    </AuthLayout>
  )
}
