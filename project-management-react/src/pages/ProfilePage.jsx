import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { z } from 'zod'
import { updateProfile, uploadAvatar } from '../api/userApi'
import { Field, inputClass, secondaryButtonClass, submitButtonClass } from '../components/ui/FormKit'
import { getLocalizedErrorMessage } from '../lib/errorMessage'
import { useAuthenticatedImage } from '../lib/useAuthenticatedImage'
import { useAuthStore } from '../stores/authStore'

function buildSchema(t) {
  return z.object({
    lastName: z.string().min(1, t('auth.validation.required')),
    firstName: z.string().min(1, t('auth.validation.required')),
    patronymic: z.string().optional(),
  })
}

export function ProfilePage() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const user = useAuthStore((state) => state.user)
  const updateUser = useAuthStore((state) => state.updateUser)
  const fileInputRef = useRef(null)

  // Аватарка больше НЕ загружается сразу по выбору файла — только по клику "Сохранить",
  // вместе с ФИО. До сохранения храним выбранный файл локально и показываем превью
  // прямо из него (URL.createObjectURL(file)), не трогая бэкенд.
  const [pendingAvatarFile, setPendingAvatarFile] = useState(null)
  const [pendingAvatarPreviewUrl, setPendingAvatarPreviewUrl] = useState(null)
  const [isSaving, setIsSaving] = useState(false)
  const [saveError, setSaveError] = useState(null)
  const [justSaved, setJustSaved] = useState(false)

  useEffect(() => {
    if (!pendingAvatarFile) {
      setPendingAvatarPreviewUrl(null)
      return
    }
    const url = URL.createObjectURL(pendingAvatarFile)
    setPendingAvatarPreviewUrl(url)
    return () => URL.revokeObjectURL(url)
  }, [pendingAvatarFile])

  const savedAvatarUrl = useAuthenticatedImage(user?.avatarUrl)
  const displayedAvatarUrl = pendingAvatarPreviewUrl ?? savedAvatarUrl

  const schema = useMemo(() => buildSchema(t), [i18n.language])

  const {
    register,
    handleSubmit,
    formState: { errors, isDirty },
  } = useForm({
    resolver: zodResolver(schema),
    // values (а не defaultValues) пересинхронизирует форму, когда user в сторе
    // меняется — например, сразу после успешного сохранения ниже.
    values: {
      lastName: user?.lastName ?? '',
      firstName: user?.firstName ?? '',
      patronymic: user?.patronymic ?? '',
    },
  })

  const hasUnsavedChanges = isDirty || pendingAvatarFile !== null

  // Предупреждение браузера при закрытии вкладки/обновлении страницы/переходе по адресу —
  // текст диалога браузеры игнорируют и показывают свой стандартный, это ограничение самого
  // beforeunload, а не наша недоработка.
  useEffect(() => {
    function handleBeforeUnload(event) {
      if (hasUnsavedChanges) {
        event.preventDefault()
        event.returnValue = ''
      }
    }
    window.addEventListener('beforeunload', handleBeforeUnload)
    return () => window.removeEventListener('beforeunload', handleBeforeUnload)
  }, [hasUnsavedChanges])

  async function onSave(values) {
    setIsSaving(true)
    setSaveError(null)
    try {
      if (pendingAvatarFile) {
        const updated = await uploadAvatar(pendingAvatarFile)
        updateUser(updated)
        setPendingAvatarFile(null)
      }
      if (isDirty) {
        const updated = await updateProfile(values)
        updateUser(updated)
      }
      setJustSaved(true)
    } catch (error) {
      setSaveError(error)
    } finally {
      setIsSaving(false)
    }
  }

  function handleBackClick(event) {
    if (hasUnsavedChanges && !window.confirm(t('profile.unsavedChangesConfirm'))) {
      event.preventDefault()
    }
  }

  return (
    <div className="mx-auto max-w-lg px-4 py-12">
      <Link to="/projects" onClick={handleBackClick} className="text-sm text-purple-600 hover:underline dark:text-purple-400">
        {t('profile.backToDashboard')}
      </Link>

      <h1 className="mt-4 mb-6 text-2xl font-semibold text-gray-900 dark:text-gray-100">{t('profile.title')}</h1>

      <div className="mb-8 flex items-center gap-4">
        <div className="h-20 w-20 shrink-0 overflow-hidden rounded-full bg-gray-200 dark:bg-gray-700">
          {displayedAvatarUrl && (
            <img src={displayedAvatarUrl} alt="" className="h-full w-full object-cover" />
          )}
        </div>
        <div>
          <input
            ref={fileInputRef}
            type="file"
            accept="image/png,image/jpeg,image/webp,image/gif"
            className="hidden"
            onChange={(event) => {
              const file = event.target.files?.[0]
              if (file) {
                setPendingAvatarFile(file)
              }
              // сбрасываем value, иначе повторный выбор того же файла не вызовет onChange
              event.target.value = ''
            }}
          />
          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            disabled={isSaving}
            className={secondaryButtonClass}
          >
            {t('profile.changeAvatar')}
          </button>
          <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t('profile.avatarHint')}</p>
          {pendingAvatarFile && (
            <p className="mt-1 text-xs text-amber-600 dark:text-amber-400">{t('profile.avatarPending')}</p>
          )}
        </div>
      </div>

      <form
        onSubmit={handleSubmit(onSave)}
        // В форме несколько текстовых полей подряд — по умолчанию браузер сабмитит форму
        // по Enter в любом из них (это НЕ переход к следующему полю, а полноценный submit).
        // Сохранение должно происходить только по явному клику на кнопку.
        onKeyDown={(event) => {
          if (event.key === 'Enter' && event.target.tagName !== 'BUTTON') {
            event.preventDefault()
          }
        }}
        className="space-y-4"
      >
        <Field label={t('profile.lastName')} error={errors.lastName?.message}>
          <input type="text" className={inputClass} {...register('lastName')} />
        </Field>
        <Field label={t('profile.firstName')} error={errors.firstName?.message}>
          <input type="text" className={inputClass} {...register('firstName')} />
        </Field>
        <Field label={t('profile.patronymic')} error={errors.patronymic?.message}>
          <input type="text" className={inputClass} {...register('patronymic')} />
        </Field>

        {saveError && <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(saveError, t)}</p>}
        {justSaved && !hasUnsavedChanges && (
          <p className="text-sm text-green-700 dark:text-green-400">{t('profile.saved')}</p>
        )}

        <button type="submit" disabled={isSaving || !hasUnsavedChanges} className={submitButtonClass}>
          {isSaving ? t('profile.saving') : t('profile.save')}
        </button>
      </form>
    </div>
  )
}
