import { zodResolver } from '@hookform/resolvers/zod'
import { useMemo } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { z } from 'zod'
import { useCreateProject } from '../../api/projectsQueries'
import { AppHeader } from '../../components/layout/AppHeader'
import { Field, inputClass, secondaryButtonClass, submitButtonClass } from '../../components/ui/FormKit'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'

function buildSchema(t) {
  return z.object({
    name: z.string().min(1, t('auth.validation.required')).max(255),
    description: z.string().optional(),
  })
}

export function NewProjectPage() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const createProject = useCreateProject()

  const schema = useMemo(() => buildSchema(t), [i18n.language, t])

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm({ resolver: zodResolver(schema) })

  function onSubmit(values) {
    createProject.mutate(values, {
      onSuccess: (project) => navigate(`/projects/${project.id}`),
    })
  }

  return (
    <div className="min-h-svh bg-gray-50">
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

          {createProject.isError && (
            <p className="text-sm text-red-600">{getLocalizedErrorMessage(createProject.error, t)}</p>
          )}

          <div className="flex gap-3">
            <button type="submit" disabled={createProject.isPending} className={submitButtonClass}>
              {createProject.isPending ? t('projects.creating') : t('projects.create')}
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
