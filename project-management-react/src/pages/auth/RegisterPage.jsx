import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useMemo } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { z } from 'zod'
import { register as registerRequest } from '../../api/authApi'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { Field, inputClass, submitButtonClass } from '../../components/ui/FormKit'
import { AuthLayout } from './authFormKit'

function buildSchema(t) {
  return z.object({
    email: z.string().min(1, t('auth.validation.required')).email(t('auth.validation.invalidEmail')),
    password: z.string().min(8, t('auth.validation.passwordMin')),
    lastName: z.string().min(1, t('auth.validation.required')),
    firstName: z.string().min(1, t('auth.validation.required')),
    patronymic: z.string().optional(),
  })
}

export function RegisterPage() {
  const { t, i18n } = useTranslation()
  const schema = useMemo(() => buildSchema(t), [i18n.language])

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm({ resolver: zodResolver(schema) })

  const mutation = useMutation({
    mutationFn: registerRequest,
  })

  if (mutation.isSuccess) {
    return (
      <AuthLayout title={t('auth.register.successTitle')}>
        <p className="text-gray-600 dark:text-gray-400">{t('auth.register.successMessage')}</p>
        <Link to="/login" className="mt-4 inline-block text-purple-600 hover:underline dark:text-purple-400">
          {t('auth.register.backToLogin')}
        </Link>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout title={t('auth.register.title')}>
      <form onSubmit={handleSubmit((values) => mutation.mutate(values))} className="space-y-4">
        <Field label={t('auth.register.email')} error={errors.email?.message}>
          <input type="email" className={inputClass} {...register('email')} />
        </Field>

        <Field label={t('auth.register.password')} error={errors.password?.message}>
          <input type="password" className={inputClass} {...register('password')} />
        </Field>

        <div className="grid grid-cols-2 gap-4">
          <Field label={t('auth.register.lastName')} error={errors.lastName?.message}>
            <input type="text" className={inputClass} {...register('lastName')} />
          </Field>
          <Field label={t('auth.register.firstName')} error={errors.firstName?.message}>
            <input type="text" className={inputClass} {...register('firstName')} />
          </Field>
        </div>

        <Field label={t('auth.register.patronymic')} error={errors.patronymic?.message}>
          <input type="text" className={inputClass} {...register('patronymic')} />
        </Field>

        {mutation.isError && (
          <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(mutation.error, t)}</p>
        )}

        <button type="submit" disabled={mutation.isPending} className={submitButtonClass}>
          {mutation.isPending ? t('auth.register.submitting') : t('auth.register.submit')}
        </button>
      </form>

      <p className="mt-4 text-sm text-gray-600 dark:text-gray-400">
        {t('auth.register.hasAccount')}{' '}
        <Link to="/login" className="text-purple-600 hover:underline dark:text-purple-400">
          {t('auth.register.login')}
        </Link>
      </p>
    </AuthLayout>
  )
}
