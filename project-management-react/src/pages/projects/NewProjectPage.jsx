import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { z } from 'zod'
import { useCreateProject, useUploadProjectPreviewImage } from '../../api/projectsQueries'
import { AppHeader } from '../../components/layout/AppHeader'
import { Field, inputClass, secondaryButtonClass, submitButtonClass } from '../../components/ui/FormKit'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { MAX_PROJECT_PREVIEW_IMAGE_SIZE_BYTES, PROJECT_PREVIEW_IMAGE_ACCEPT } from '../../lib/constants'
import { useToastStore } from '../../stores/toastStore'

function buildSchema(t) {
  return z.object({
    // Только печатаемая латиница/ASCII — имя используется для генерации slug в URL
    // (/projects/{slug}/...), зеркалирует @Pattern на CreateProjectRequest (бэкенд).
    name: z
      .string()
      .min(1, t('auth.validation.required'))
      .max(255)
      .regex(/^[\x20-\x7E]+$/, t('projects.validation.nameLatinOnly')),
    description: z.string().optional(),
  })
}

export function NewProjectPage() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const createProject = useCreateProject()
  const uploadPreviewImage = useUploadProjectPreviewImage()
  const pushToast = useToastStore((state) => state.pushToast)
  const fileInputRef = useRef(null)

  // Как и с аватаркой пользователя (ProfilePage) — картинка не загружается сразу по выбору
  // файла, а хранится локально и уходит на сервер только после успешного создания проекта
  // (см. onSubmit), т.к. эндпоинт загрузки привязан к уже существующему id проекта.
  const [pendingImageFile, setPendingImageFile] = useState(null)
  const [pendingImagePreviewUrl, setPendingImagePreviewUrl] = useState(null)
  const [imageError, setImageError] = useState(null)

  useEffect(() => {
    if (!pendingImageFile) {
      setPendingImagePreviewUrl(null)
      return
    }
    const url = URL.createObjectURL(pendingImageFile)
    setPendingImagePreviewUrl(url)
    return () => URL.revokeObjectURL(url)
  }, [pendingImageFile])

  const schema = useMemo(() => buildSchema(t), [i18n.language, t])

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm({ resolver: zodResolver(schema) })

  function handleImageSelected(event) {
    const file = event.target.files?.[0]
    // сбрасываем value сразу, иначе повторный выбор того же файла не вызовет onChange
    event.target.value = ''
    if (!file) {
      return
    }
    setImageError(null)

    if (file.size > MAX_PROJECT_PREVIEW_IMAGE_SIZE_BYTES) {
      setImageError(t('projects.validation.previewImageTooLarge'))
      return
    }

    setPendingImageFile(file)
  }

  async function onSubmit(values) {
    let project
    try {
      project = await createProject.mutateAsync(values)
    } catch {
      // createProject.isError уже отражает это инлайн-баннером ниже
      return
    }

    if (pendingImageFile) {
      try {
        await uploadPreviewImage.mutateAsync({ projectId: project.id, file: pendingImageFile })
      } catch (error) {
        // Проект уже создан — не блокируем переход из-за вторичной по значимости ошибки
        // картинки, только предупреждаем тостом (тот же приём, что и app.sessionExpired).
        pushToast(getLocalizedErrorMessage(error, t), 'error')
      }
    }

    navigate(`/projects/${project.slug}`)
  }

  return (
    <div className="min-h-svh">
      <AppHeader />

      <div className="mx-auto max-w-lg px-4 py-12">
        <Link to="/projects" className="text-sm text-purple-600 hover:underline">
          {t('projects.backToList')}
        </Link>

        <h1 className="mt-4 mb-6 text-2xl font-semibold text-gray-900">{t('projects.createTitle')}</h1>

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          <Field label={t('projects.name')} error={errors.name?.message}>
            <input type="text" className={inputClass} {...register('name')} />
          </Field>
          <Field label={t('projects.description')} error={errors.description?.message}>
            <textarea rows={4} className={inputClass} {...register('description')} />
          </Field>

          <div className="flex items-center gap-4">
            <div className="h-20 w-20 shrink-0 overflow-hidden rounded-lg bg-gray-200">
              {pendingImagePreviewUrl && (
                <img src={pendingImagePreviewUrl} alt="" className="h-full w-full object-cover" />
              )}
            </div>
            <div>
              <input
                ref={fileInputRef}
                type="file"
                accept={PROJECT_PREVIEW_IMAGE_ACCEPT}
                className="hidden"
                onChange={handleImageSelected}
              />
              <button
                type="button"
                onClick={() => fileInputRef.current?.click()}
                disabled={createProject.isPending || uploadPreviewImage.isPending}
                className={secondaryButtonClass}
              >
                {t('projects.previewImage')}
              </button>
              <p className="mt-1 text-xs text-gray-500">{t('projects.previewImageHint')}</p>
              {imageError && <p className="mt-1 text-xs text-red-600">{imageError}</p>}
            </div>
          </div>

          {createProject.isError && (
            <p className="text-sm text-red-600">{getLocalizedErrorMessage(createProject.error, t)}</p>
          )}

          <div className="flex gap-3">
            <button
              type="submit"
              disabled={createProject.isPending || uploadPreviewImage.isPending}
              className={submitButtonClass}
            >
              {createProject.isPending || uploadPreviewImage.isPending ? t('projects.creating') : t('projects.create')}
            </button>
            <Link to="/projects" className={secondaryButtonClass}>
              {t('projects.cancel')}
            </Link>
          </div>
        </form>
      </div>
    </div>
  )
}
