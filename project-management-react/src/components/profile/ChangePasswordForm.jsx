import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useMemo } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { z } from 'zod'
import { changePassword } from '../../api/userApi'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { useAuthStore } from '../../stores/authStore'
import { useToastStore } from '../../stores/toastStore'
import { Field, inputClass, submitButtonClass } from '../ui/FormKit'

function buildSchema(t) {
  return z
    .object({
      currentPassword: z.string().min(1, t('auth.validation.required')),
      newPassword: z.string().min(8, t('auth.validation.passwordMin')),
      confirmPassword: z.string().min(1, t('auth.validation.required')),
    })
    .refine((values) => values.newPassword === values.confirmPassword, {
      path: ['confirmPassword'],
      message: t('auth.validation.passwordsDoNotMatch'),
    })
}

export function ChangePasswordForm() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const clearSession = useAuthStore((state) => state.clearSession)
  const pushToast = useToastStore((state) => state.pushToast)
  const schema = useMemo(() => buildSchema(t), [i18n.language])

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm({ resolver: zodResolver(schema) })

  const mutation = useMutation({
    mutationFn: (values) => changePassword(values.currentPassword, values.newPassword),
    // Бэкенд гасит ВСЕ refresh-токены пользователя, включая токен этой сессии, — иначе смена
    // пароля не выгоняла бы того, из-за кого её затеяли. Поэтому разлогиниваемся сами и сразу:
    // иначе вкладка доживёт до истечения access-токена и упадёт на молчаливом 401.
    onSuccess: () => {
      reset()
      clearSession()
      pushToast(t('profile.password.changedSignInAgain'))
      navigate('/login', { replace: true })
    },
  })

  return (
    <section className="mt-10 border-t border-gray-200 pt-8 dark:border-gray-700">
      <h2 className="mb-1 text-lg font-semibold text-gray-900 dark:text-gray-100">
        {t('profile.password.title')}
      </h2>
      <p className="mb-4 text-sm text-gray-600 dark:text-gray-400">{t('profile.password.hint')}</p>

      <form onSubmit={handleSubmit((values) => mutation.mutate(values))} className="space-y-4">
        <Field label={t('profile.password.current')} error={errors.currentPassword?.message}>
          <input type="password" className={inputClass} autoComplete="current-password" {...register('currentPassword')} />
        </Field>

        <Field label={t('profile.password.new')} error={errors.newPassword?.message}>
          <input type="password" className={inputClass} autoComplete="new-password" {...register('newPassword')} />
        </Field>

        <Field label={t('profile.password.confirm')} error={errors.confirmPassword?.message}>
          <input type="password" className={inputClass} autoComplete="new-password" {...register('confirmPassword')} />
        </Field>

        {mutation.isError && (
          <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(mutation.error, t)}</p>
        )}

        <button type="submit" disabled={mutation.isPending} className={submitButtonClass}>
          {mutation.isPending ? t('profile.password.submitting') : t('profile.password.submit')}
        </button>
      </form>
    </section>
  )
}
