import { zodResolver } from '@hookform/resolvers/zod'
import { useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { useParams } from 'react-router-dom'
import { z } from 'zod'
import { useProjectBySlug, useProjectMembers } from '../../api/projectsQueries'
import { useCreateTag, useDeleteTag, useTags, useUpdateTag } from '../../api/tagsQueries'
import { Field, inputClass, primaryButtonClass } from '../../components/ui/FormKit'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { tagBadgeStyle } from '../../lib/tagColor'
import { useAuthStore } from '../../stores/authStore'

const DEFAULT_COLOR = '#8b5cf6'

function buildTagSchema(t) {
  return z.object({
    name: z.string().min(1, t('auth.validation.required')).max(100),
    color: z.string().regex(/^#[0-9A-Fa-f]{6}$/, t('tags.validation.invalidColor')),
  })
}

export function ProjectTagsPage() {
  const { t, i18n } = useTranslation()
  const { projectSlug } = useParams()
  const currentUser = useAuthStore((state) => state.user)

  const { data: project } = useProjectBySlug(projectSlug)
  const projectId = project?.id
  const { data: members } = useProjectMembers(projectId)
  const { data: tags, isLoading, isError, error } = useTags(projectId)
  const createTag = useCreateTag(projectId)
  const updateTag = useUpdateTag(projectId)
  const deleteTag = useDeleteTag(projectId)

  const [editingTagId, setEditingTagId] = useState(null)

  // Единственное owner-only место в приложении (в отличие от ADMIN+ у ProjectMembersPage) —
  // точное сравнение роли, не roleIsAtLeast. Сервер всё равно проверяет права на каждом
  // write-эндпоинте (INSUFFICIENT_ROLE), это только косметическое скрытие UI.
  const myMembership = members?.find((member) => member.userId === currentUser?.id)
  const canManage = myMembership?.role === 'OWNER'

  const schema = useMemo(() => buildTagSchema(t), [i18n.language, t])

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm({ resolver: zodResolver(schema), defaultValues: { name: '', color: DEFAULT_COLOR } })

  const {
    register: registerEdit,
    handleSubmit: handleSubmitEdit,
    reset: resetEdit,
    formState: { errors: editErrors },
  } = useForm({ resolver: zodResolver(schema) })

  function onCreate(values) {
    createTag.mutate(values, { onSuccess: () => reset({ name: '', color: DEFAULT_COLOR }) })
  }

  function startEdit(tag) {
    setEditingTagId(tag.id)
    resetEdit({ name: tag.name, color: tag.color })
  }

  function onSaveEdit(values) {
    updateTag.mutate({ tagId: editingTagId, payload: values }, { onSuccess: () => setEditingTagId(null) })
  }

  function onDelete(tagId) {
    if (!window.confirm(t('tags.deleteConfirm'))) {
      return
    }
    deleteTag.mutate(tagId)
  }

  return (
      <div className="mx-auto max-w-2xl px-4 py-8">
        <h1 className="mb-6 text-2xl font-semibold text-gray-900 dark:text-gray-100">{t('tags.navLabel')}</h1>

        {canManage && (
          <form
            onSubmit={handleSubmit(onCreate)}
            className="mb-6 flex flex-wrap items-end gap-3 rounded-lg border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-800"
          >
            <div className="min-w-48 flex-1">
              <Field label={t('tags.name')} error={errors.name?.message}>
                <input type="text" className={inputClass} {...register('name')} />
              </Field>
            </div>
            <div>
              <Field label={t('tags.color')} error={errors.color?.message}>
                <input type="color" className="h-10 w-16 rounded-md border border-gray-300 dark:border-gray-600" {...register('color')} />
              </Field>
            </div>
            <button type="submit" disabled={createTag.isPending} className={primaryButtonClass}>
              {createTag.isPending ? t('tags.creating') : t('tags.create')}
            </button>
          </form>
        )}

        {createTag.isError && (
          <p className="mb-4 text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(createTag.error, t)}</p>
        )}
        {updateTag.isError && (
          <p className="mb-4 text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(updateTag.error, t)}</p>
        )}
        {deleteTag.isError && (
          <p className="mb-4 text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(deleteTag.error, t)}</p>
        )}

        {isLoading && <p className="text-gray-500 dark:text-gray-400">{t('tags.loading')}</p>}
        {isError && <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(error, t)}</p>}

        {!isLoading && !isError && tags && (
          <ul className="divide-y divide-gray-200 rounded-lg border border-gray-200 bg-white dark:divide-gray-700 dark:border-gray-700 dark:bg-gray-800">
            {tags.length === 0 && <li className="px-4 py-3 text-sm text-gray-400 dark:text-gray-500">{t('tags.empty')}</li>}
            {tags.map((tag) =>
              editingTagId === tag.id ? (
                <li key={tag.id} className="px-4 py-3">
                  <form
                    onSubmit={handleSubmitEdit(onSaveEdit)}
                    className="flex flex-wrap items-end gap-3"
                  >
                    <div className="min-w-48 flex-1">
                      <Field label={t('tags.name')} error={editErrors.name?.message}>
                        <input type="text" className={inputClass} {...registerEdit('name')} />
                      </Field>
                    </div>
                    <div>
                      <Field label={t('tags.color')} error={editErrors.color?.message}>
                        <input
                          type="color"
                          className="h-10 w-16 rounded-md border border-gray-300 dark:border-gray-600"
                          {...registerEdit('color')}
                        />
                      </Field>
                    </div>
                    <button type="submit" disabled={updateTag.isPending} className={primaryButtonClass}>
                      {t('tags.save')}
                    </button>
                    <button
                      type="button"
                      onClick={() => setEditingTagId(null)}
                      className="text-sm text-gray-500 hover:underline dark:text-gray-400"
                    >
                      {t('tags.cancel')}
                    </button>
                  </form>
                </li>
              ) : (
                <li key={tag.id} className="flex items-center justify-between gap-4 px-4 py-3">
                  <span
                    className="rounded-full px-2 py-0.5 text-xs font-medium"
                    style={tagBadgeStyle(tag.color)}
                  >
                    {tag.name}
                  </span>

                  {canManage && (
                    <div className="flex items-center gap-3">
                      <button
                        type="button"
                        onClick={() => startEdit(tag)}
                        className="text-xs text-purple-600 hover:underline dark:text-purple-400"
                      >
                        {t('tags.edit')}
                      </button>
                      <button
                        type="button"
                        onClick={() => onDelete(tag.id)}
                        disabled={deleteTag.isPending}
                        className="text-xs text-red-600 hover:underline disabled:opacity-60 dark:text-red-400"
                      >
                        {t('tags.delete')}
                      </button>
                    </div>
                  )}
                </li>
              ),
            )}
          </ul>
        )}
      </div>
  )
}
