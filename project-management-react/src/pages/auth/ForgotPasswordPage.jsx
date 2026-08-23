import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useMemo } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { z } from 'zod'
import { forgotPassword } from '../../api/authApi'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { Field, inputClass, submitButtonClass } from '../../components/ui/FormKit'
import { AuthLayout } from './authFormKit'

function buildSchema(t) {
  return z.object({
    email: z.string().min(1, t('auth.validation.required')).email(t('auth.validation.invalidEmail')),
  })
}

export function ForgotPasswordPage() {
  const { t, i18n } = useTranslation()
  const schema = useMemo(() => buildSchema(t), [i18n.language])

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm({ resolver: zodResolver(schema) })

  const mutation = useMutation({ mutationFn: forgotPassword })

  // Экран успеха намеренно не говорит, нашёлся аккаунт или нет: бэкенд отвечает одинаково
  // в обоих случаях (см. AuthController.forgotPassword), и текст здесь не должен это выдавать.
  if (mutation.isSuccess) {
    return (
      <AuthLayout title={t('auth.forgotPassword.successTitle')}>
        <p className="text-gray-600 dark:text-gray-400">{t('auth.forgotPassword.successMessage')}</p>
        <Link to="/login" className="mt-4 inline-block text-purple-600 hover:underline dark:text-purple-400">
          {t('auth.forgotPassword.backToLogin')}
        </Link>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout title={t('auth.forgotPassword.title')}>
      <p className="mb-4 text-sm text-gray-600 dark:text-gray-400">{t('auth.forgotPassword.hint')}</p>

      <form onSubmit={handleSubmit((values) => mutation.mutate(values.email))} className="space-y-4">
        <Field label={t('auth.forgotPassword.email')} error={errors.email?.message}>
          <input type="email" className={inputClass} maxLength={255} {...register('email')} />
        </Field>

        {mutation.isError && (
          <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(mutation.error, t)}</p>
        )}

        <button type="submit" disabled={mutation.isPending} className={submitButtonClass}>
          {mutation.isPending ? t('auth.forgotPassword.submitting') : t('auth.forgotPassword.submit')}
        </button>
      </form>

      <p className="mt-4 text-sm text-gray-600 dark:text-gray-400">
        <Link to="/login" className="text-purple-600 hover:underline dark:text-purple-400">
          {t('auth.forgotPassword.backToLogin')}
        </Link>
      </p>
    </AuthLayout>
  )
}
