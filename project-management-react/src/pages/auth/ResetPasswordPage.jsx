import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useMemo } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router-dom'
import { z } from 'zod'
import { resetPassword } from '../../api/authApi'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { Field, inputClass, submitButtonClass } from '../../components/ui/FormKit'
import { AuthLayout } from './authFormKit'

function buildSchema(t) {
  return z
    .object({
      newPassword: z.string().min(8, t('auth.validation.passwordMin')),
      confirmPassword: z.string().min(1, t('auth.validation.required')),
    })
    .refine((values) => values.newPassword === values.confirmPassword, {
      path: ['confirmPassword'],
      message: t('auth.validation.passwordsDoNotMatch'),
    })
}

export function ResetPasswordPage() {
  const { t, i18n } = useTranslation()
  const [searchParams] = useSearchParams()
  const token = searchParams.get('token')
  const schema = useMemo(() => buildSchema(t), [i18n.language])

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm({ resolver: zodResolver(schema) })

  const mutation = useMutation({
    mutationFn: (values) => resetPassword(token, values.newPassword),
  })

  if (!token) {
    return (
      <AuthLayout title={t('auth.resetPassword.title')}>
        <p className="text-red-600 dark:text-red-400">{t('auth.resetPassword.missingToken')}</p>
        <Link to="/forgot-password" className="mt-4 inline-block text-purple-600 hover:underline dark:text-purple-400">
          {t('auth.resetPassword.requestNewLink')}
        </Link>
      </AuthLayout>
    )
  }

  if (mutation.isSuccess) {
    return (
      <AuthLayout title={t('auth.resetPassword.successTitle')}>
        <p className="text-green-700 dark:text-green-400">{t('auth.resetPassword.successMessage')}</p>
        <Link to="/login" className="mt-4 inline-block text-purple-600 hover:underline dark:text-purple-400">
          {t('auth.resetPassword.goToLogin')}
        </Link>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout title={t('auth.resetPassword.title')}>
      <form onSubmit={handleSubmit((values) => mutation.mutate(values))} className="space-y-4">
        <Field label={t('auth.resetPassword.newPassword')} error={errors.newPassword?.message}>
          <input type="password" className={inputClass} autoComplete="new-password" {...register('newPassword')} />
        </Field>

        <Field label={t('auth.resetPassword.confirmPassword')} error={errors.confirmPassword?.message}>
          <input type="password" className={inputClass} autoComplete="new-password" {...register('confirmPassword')} />
        </Field>

        {mutation.isError && (
          <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(mutation.error, t)}</p>
        )}

        <button type="submit" disabled={mutation.isPending} className={submitButtonClass}>
          {mutation.isPending ? t('auth.resetPassword.submitting') : t('auth.resetPassword.submit')}
        </button>
      </form>

      {/* Ссылка живёт час и срабатывает один раз — промахнувшемуся нужен путь назад,
          иначе единственный выход это вручную вспомнить адрес /forgot-password. */}
      <p className="mt-4 text-sm text-gray-600 dark:text-gray-400">
        <Link to="/forgot-password" className="text-purple-600 hover:underline dark:text-purple-400">
          {t('auth.resetPassword.requestNewLink')}
        </Link>
      </p>
    </AuthLayout>
  )
}
