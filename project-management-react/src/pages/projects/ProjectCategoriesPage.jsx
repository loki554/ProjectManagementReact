import { zodResolver } from '@hookform/resolvers/zod'
import { useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { useParams } from 'react-router-dom'
import { z } from 'zod'
import {
  useCategories,
  useCreateCategory,
  useDeleteCategory,
  useUpdateCategory,
} from '../../api/categoriesQueries'
import { useProjectBySlug, useProjectMembers } from '../../api/projectsQueries'
import { Field, inputClass, primaryButtonClass } from '../../components/ui/FormKit'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { useAuthStore } from '../../stores/authStore'

function buildCategorySchema(t) {
  return z.object({
    name: z.string().min(1, t('auth.validation.required')).max(100),
  })
}

export function ProjectCategoriesPage() {
  const { t, i18n } = useTranslation()
  const { projectSlug } = useParams()
  const currentUser = useAuthStore((state) => state.user)

  const { data: project } = useProjectBySlug(projectSlug)
  const projectId = project?.id
  const { data: members } = useProjectMembers(projectId)
  const { data: categories, isLoading, isError, error } = useCategories(projectId)
  const createCategory = useCreateCategory(projectId)
  const updateCategory = useUpdateCategory(projectId)
  const deleteCategory = useDeleteCategory(projectId)

  const [editingCategoryId, setEditingCategoryId] = useState(null)

  // Owner-only, как на странице тэгов — сервер всё равно проверяет роль на каждом write-
  // эндпоинте (INSUFFICIENT_ROLE), здесь только косметическое скрытие UI. Обычный участник
  // при этом не заблокирован: новая категория заводится свободным вводом в форме задачи.
  const myMembership = members?.find((member) => member.userId === currentUser?.id)
  const canManage = myMembership?.role === 'OWNER'

  const schema = useMemo(() => buildCategorySchema(t), [i18n.language, t])

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm({ resolver: zodResolver(schema), defaultValues: { name: '' } })

  const {
    register: registerEdit,
    handleSubmit: handleSubmitEdit,
    reset: resetEdit,
    formState: { errors: editErrors },
  } = useForm({ resolver: zodResolver(schema) })

  function onCreate(values) {
    createCategory.mutate(values, { onSuccess: () => reset({ name: '' }) })
  }

  function startEdit(category) {
    setEditingCategoryId(category.id)
    resetEdit({ name: category.name })
  }

  function onSaveEdit(values) {
    updateCategory.mutate(
      { categoryId: editingCategoryId, payload: values },
      { onSuccess: () => setEditingCategoryId(null) },
    )
  }

  function onDelete(category) {
    // В отличие от тэга показываем в подтверждении число задач: удаление снимает категорию
    // со всех них, и масштаб последствий должен быть виден до нажатия «ОК».
    const message = category.taskCount
      ? t('categories.deleteConfirmWithTasks', { count: category.taskCount })
      : t('categories.deleteConfirm')
    if (!window.confirm(message)) {
      return
    }
    deleteCategory.mutate(category.id)
  }

  return (
    <div className="mx-auto max-w-2xl px-4 py-8">
      <h1 className="mb-2 text-2xl font-semibold text-gray-900 dark:text-gray-100">
        {t('categories.navLabel')}
      </h1>
      <p className="mb-6 text-sm text-gray-500 dark:text-gray-400">{t('categories.hint')}</p>

      {canManage && (
        <form
          onSubmit={handleSubmit(onCreate)}
          className="mb-6 flex flex-wrap items-end gap-3 rounded-lg border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-800"
        >
          <div className="min-w-48 flex-1">
            <Field label={t('categories.name')} error={errors.name?.message}>
              <input type="text" className={inputClass} maxLength={100} {...register('name')} />
            </Field>
          </div>
          <button type="submit" disabled={createCategory.isPending} className={primaryButtonClass}>
            {createCategory.isPending ? t('categories.creating') : t('categories.create')}
          </button>
        </form>
      )}

      {createCategory.isError && (
        <p className="mb-4 text-sm text-red-600 dark:text-red-400">
          {getLocalizedErrorMessage(createCategory.error, t)}
        </p>
      )}
      {updateCategory.isError && (
        <p className="mb-4 text-sm text-red-600 dark:text-red-400">
          {getLocalizedErrorMessage(updateCategory.error, t)}
        </p>
      )}
      {deleteCategory.isError && (
        <p className="mb-4 text-sm text-red-600 dark:text-red-400">
          {getLocalizedErrorMessage(deleteCategory.error, t)}
        </p>
      )}

      {isLoading && <p className="text-gray-500 dark:text-gray-400">{t('categories.loading')}</p>}
      {isError && <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(error, t)}</p>}

      {!isLoading && !isError && categories && (
        <ul className="divide-y divide-gray-200 rounded-lg border border-gray-200 bg-white dark:divide-gray-700 dark:border-gray-700 dark:bg-gray-800">
          {categories.length === 0 && (
            <li className="px-4 py-3 text-sm text-gray-400 dark:text-gray-500">{t('categories.empty')}</li>
          )}
          {categories.map((category) =>
            editingCategoryId === category.id ? (
              <li key={category.id} className="px-4 py-3">
                <form onSubmit={handleSubmitEdit(onSaveEdit)} className="flex flex-wrap items-end gap-3">
                  <div className="min-w-48 flex-1">
                    <Field label={t('categories.name')} error={editErrors.name?.message}>
                      <input type="text" className={inputClass} maxLength={100} {...registerEdit('name')} />
                    </Field>
                  </div>
                  <button type="submit" disabled={updateCategory.isPending} className={primaryButtonClass}>
                    {t('categories.save')}
                  </button>
                  <button
                    type="button"
                    onClick={() => setEditingCategoryId(null)}
                    className="text-sm text-gray-500 hover:underline dark:text-gray-400"
                  >
                    {t('categories.cancel')}
                  </button>
                </form>
              </li>
            ) : (
              <li key={category.id} className="flex items-center justify-between gap-4 px-4 py-3">
                <div className="flex min-w-0 items-center gap-3">
                  <span className="truncate rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600 dark:bg-gray-700 dark:text-gray-300">
                    {category.name}
                  </span>
                  <span className="shrink-0 text-xs text-gray-400 dark:text-gray-500">
                    {t('categories.taskCount', { count: category.taskCount })}
                  </span>
                </div>

                {canManage && (
                  <div className="flex shrink-0 items-center gap-3">
                    <button
                      type="button"
                      onClick={() => startEdit(category)}
                      className="text-xs text-purple-600 hover:underline dark:text-purple-400"
                    >
                      {t('categories.edit')}
                    </button>
                    <button
                      type="button"
                      onClick={() => onDelete(category)}
                      disabled={deleteCategory.isPending}
                      className="text-xs text-red-600 hover:underline disabled:opacity-60 dark:text-red-400"
                    >
                      {t('categories.delete')}
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
