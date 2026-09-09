import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { z } from 'zod'
import {
  useProjectBySlug,
  useSetProjectArchived,
  useUpdateProject,
  useUploadProjectPreviewImage,
} from '../../api/projectsQueries'
import { Field, inputClass, secondaryButtonClass, submitButtonClass } from '../../components/ui/FormKit'
import { MAX_PROJECT_PREVIEW_IMAGE_SIZE_BYTES, PROJECT_PREVIEW_IMAGE_ACCEPT } from '../../lib/constants'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { confirmAction } from '../../stores/confirmStore'
import { useAuthenticatedImage } from '../../lib/useAuthenticatedImage'
import { useToastStore } from '../../stores/toastStore'

function buildSchema(t) {
  // В отличие от создания (NewProjectPage) — без latin-only ограничения: slug
  // генерируется один раз при создании и при переименовании не меняется
  // (зеркало UpdateProjectRequest на бэкенде, там нет @Pattern).
  return z.object({
    name: z.string().min(1, t('auth.validation.required')).max(255),
    description: z.string().optional(),
  })
}

export function ProjectEditPage() {
  const { t, i18n } = useTranslation()
  const { projectSlug } = useParams()
  const navigate = useNavigate()
  const pushToast = useToastStore((state) => state.pushToast)
  const fileInputRef = useRef(null)

  const { data: project, isLoading, isError, error } = useProjectBySlug(projectSlug)
  const updateProject = useUpdateProject(project?.id)
  const setArchived = useSetProjectArchived(project?.id)
  const uploadPreviewImage = useUploadProjectPreviewImage()
  const currentImageUrl = useAuthenticatedImage(project?.previewImageUrl)

  // Тот же приём, что и в NewProjectPage: новая картинка не загружается сразу по
  // выбору файла, а хранится локально и уходит на сервер вместе с сохранением формы.
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
    formState: { errors, isDirty },
  } = useForm({
    resolver: zodResolver(schema),
    // values (не defaultValues) держит форму синхронизированной с кэшем react-query —
    // тот же приём, что и в TaskEditPage/ProfilePage.
    values: project
      ? {
          name: project.name,
          description: project.description ?? '',
        }
      : undefined,
  })

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
    try {
      await updateProject.mutateAsync({
        name: values.name,
        description: values.description || null,
        // См. TaskEditPage: версия на момент открытия формы, 409 вместо тихой перезаписи.
        version: project.version,
      })
    } catch {
      // updateProject.isError уже отражает это инлайн-баннером ниже
      return
    }

    if (pendingImageFile) {
      try {
        await uploadPreviewImage.mutateAsync({ projectId: project.id, file: pendingImageFile })
      } catch (error) {
        // Данные уже сохранены — не блокируем переход из-за вторичной по значимости
        // ошибки картинки, только предупреждаем тостом (как в NewProjectPage).
        pushToast(getLocalizedErrorMessage(error, t), 'error')
      }
    }

    navigate(`/projects/${projectSlug}`)
  }

  if (isLoading) {
    return <p className="px-4 py-8 text-gray-500 dark:text-gray-400">{t('projectOverview.loading')}</p>
  }
  if (isError) {
    return <p className="px-4 py-8 text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(error, t)}</p>
  }
  if (!project) {
    return null
  }
  // Косметическое скрытие — сервер всё равно вернёт INSUFFICIENT_ROLE на PATCH.
  if (project.myRole !== 'OWNER') {
    return <p className="px-4 py-8 text-sm text-red-600 dark:text-red-400">{t('errors.INSUFFICIENT_ROLE')}</p>
  }
  // Настройки архивного проекта не правятся (4.14). Показываем не форму, а причину и
  // выход из неё — иначе владелец заполнил бы её и получил 409 на сохранении.
  if (project.archived) {
    return (
      <div className="mx-auto max-w-lg px-4 py-8">
        <p className="text-sm text-gray-600 dark:text-gray-400">{t('projectEdit.archivedNotice')}</p>
        <Link to={`/projects/${projectSlug}`} className={`mt-4 inline-block ${secondaryButtonClass}`}>
          {t('projects.backToProject')}
        </Link>
      </div>
    )
  }

  const isPending = updateProject.isPending || uploadPreviewImage.isPending

  return (
    <div className="mx-auto max-w-lg px-4 py-8">
      <h1 className="mb-6 text-2xl font-semibold text-gray-900 dark:text-gray-100">{t('projectEdit.title')}</h1>

      <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
        <Field label={t('projects.name')} error={errors.name?.message}>
          <input type="text" className={inputClass} maxLength={255} {...register('name')} />
        </Field>
        <Field label={t('projects.description')} error={errors.description?.message}>
          <textarea rows={4} className={inputClass} maxLength={20000} {...register('description')} />
        </Field>

        <div className="flex items-center gap-4">
          <div className="h-20 w-20 shrink-0 overflow-hidden rounded-lg bg-gray-200 dark:bg-gray-700">
            {(pendingImagePreviewUrl ?? currentImageUrl) && (
              <img
                src={pendingImagePreviewUrl ?? currentImageUrl}
                alt=""
                className="h-full w-full object-cover"
              />
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
              disabled={isPending}
              className={secondaryButtonClass}
            >
              {t('projectEdit.changeImage')}
            </button>
            <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t('projects.previewImageHint')}</p>
            {imageError && <p className="mt-1 text-xs text-red-600 dark:text-red-400">{imageError}</p>}
          </div>
        </div>

        {updateProject.isError && (
          <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(updateProject.error, t)}</p>
        )}

        <div className="flex gap-3">
          <button
            type="submit"
            disabled={isPending || (!isDirty && !pendingImageFile)}
            className={submitButtonClass}
          >
            {isPending ? t('projectEdit.saving') : t('projectEdit.save')}
          </button>
          <Link to={`/projects/${projectSlug}`} className={secondaryButtonClass}>
            {t('projects.cancel')}
          </Link>
        </div>
      </form>

      {/* Архивация (4.14) — за чертой и вне формы: это не поле настроек, а действие с
          последствиями (проект уходит из списка и перестаёт принимать правки), и оно не
          должно ни ехать вместе с переименованием, ни спотыкаться о его версию. */}
      <div className="mt-8 border-t border-gray-200 pt-6 dark:border-gray-700">
        <h2 className="text-sm font-semibold text-gray-900 dark:text-gray-100">{t('projectEdit.archiveTitle')}</h2>
        <p className="mt-1 mb-3 text-sm text-gray-500 dark:text-gray-400">{t('projectEdit.archiveHint')}</p>
        <button
          type="button"
          onClick={async () => {
            const confirmed = await confirmAction({
              title: t('projectEdit.archiveConfirm'),
              body: t('projectEdit.archiveConfirmBody'),
              confirmLabel: t('projectEdit.archiveAction'),
              // Не «опасное» действие: архив обратим, и красная кнопка обещала бы
              // последствия страшнее тех, что есть на самом деле (4.14).
              tone: 'primary',
            })
            if (confirmed) {
              setArchived.mutate(true, { onSuccess: () => navigate(`/projects/${projectSlug}`) })
            }
          }}
          disabled={setArchived.isPending}
          className={secondaryButtonClass}
        >
          {setArchived.isPending ? t('projectEdit.archiving') : t('projectEdit.archive')}
        </button>
        {setArchived.isError && (
          <p className="mt-2 text-sm text-red-600 dark:text-red-400">
            {getLocalizedErrorMessage(setArchived.error, t)}
          </p>
        )}
      </div>
    </div>
  )
}
