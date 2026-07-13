import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useMemo } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { z } from 'zod'
import { login, resendVerification } from '../../api/authApi'
import { getErrorCode, getLocalizedErrorMessage } from '../../lib/errorMessage'
import { useAuthStore } from '../../stores/authStore'
import { Field, inputClass, submitButtonClass } from '../../components/ui/FormKit'
import { AuthLayout } from './authFormKit'

function buildSchema(t) {
  return z.object({
    email: z.string().min(1, t('auth.validation.required')).email(t('auth.validation.invalidEmail')),
    password: z.string().min(1, t('auth.validation.required')),
  })
}

export function LoginPage() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const setSession = useAuthStore((state) => state.setSession)
  const schema = useMemo(() => buildSchema(t), [i18n.language])

  const {
    register,
    handleSubmit,
    getValues,
    formState: { errors },
  } = useForm({ resolver: zodResolver(schema) })

  const loginMutation = useMutation({
    mutationFn: ({ email, password }) => login(email, password),
    onSuccess: (data) => {
      setSession(data)
      navigate('/', { replace: true })
    },
  })

  const resendMutation = useMutation({
    mutationFn: resendVerification,
  })

  const isEmailNotVerified = getErrorCode(loginMutation.error) === 'EMAIL_NOT_VERIFIED'

  return (
    <AuthLayout title={t('auth.login.title')}>
      <form
        onSubmit={handleSubmit((values) => loginMutation.mutate(values))}
        className="space-y-4"
      >
        <Field label={t('auth.login.email')} error={errors.email?.message}>
          <input type="email" className={inputClass} {...register('email')} />
        </Field>

        <Field label={t('auth.login.password')} error={errors.password?.message}>
          <input type="password" className={inputClass} {...register('password')} />
        </Field>

        {loginMutation.isError && !isEmailNotVerified && (
          <p className="text-sm text-red-600 dark:text-red-400">
            {getLocalizedErrorMessage(loginMutation.error, t)}
          </p>
        )}

        {isEmailNotVerified && (
          <div className="rounded-md bg-amber-50 p-3 text-sm text-amber-800 dark:bg-amber-950/50 dark:text-amber-300">
            <p>{getLocalizedErrorMessage(loginMutation.error, t)}</p>
            {resendMutation.isSuccess ? (
              <p className="mt-2 font-medium">{t('auth.login.resendVerificationSuccess')}</p>
            ) : (
              <button
                type="button"
                onClick={() => resendMutation.mutate(getValues('email'))}
                disabled={resendMutation.isPending}
                className="mt-2 font-medium text-purple-700 hover:underline disabled:opacity-60 dark:text-purple-400"
              >
                {resendMutation.isPending
                  ? t('auth.login.resendVerificationPending')
                  : t('auth.login.resendVerification')}
              </button>
            )}
          </div>
        )}

        <button type="submit" disabled={loginMutation.isPending} className={submitButtonClass}>
          {loginMutation.isPending ? t('auth.login.submitting') : t('auth.login.submit')}
        </button>
      </form>

      <p className="mt-4 text-sm text-gray-600 dark:text-gray-400">
        {t('auth.login.noAccount')}{' '}
        <Link to="/register" className="text-purple-600 hover:underline dark:text-purple-400">
          {t('auth.login.register')}
        </Link>
      </p>
    </AuthLayout>
  )
}
