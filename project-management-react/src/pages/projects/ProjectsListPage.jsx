import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { useProjects } from '../../api/projectsQueries'
import { AppHeader } from '../../components/layout/AppHeader'
import { primaryButtonClass } from '../../components/ui/FormKit'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'

export function ProjectsListPage() {
  const { t } = useTranslation()
  const { data: projects, isLoading, isError, error } = useProjects()

  return (
    <div className="min-h-svh bg-gray-50">
      <AppHeader />

      <div className="mx-auto max-w-4xl px-4 py-8">
        <div className="mb-6 flex items-center justify-between">
          <h1 className="text-2xl font-semibold text-gray-900">{t('projects.title')}</h1>
          <Link to="/projects/new" className={primaryButtonClass}>
            {t('projects.newProject')}
          </Link>
        </div>

        {isLoading && <p className="text-gray-500">{t('projects.loading')}</p>}
        {isError && <p className="text-sm text-red-600">{getLocalizedErrorMessage(error, t)}</p>}

        {!isLoading && !isError && projects.length === 0 && (
          <div className="flex flex-col items-center justify-center rounded-lg border border-dashed border-gray-300 bg-white py-16 text-center">
            <p className="text-gray-600">{t('projects.empty')}</p>
            <Link to="/projects/new" className={`mt-4 ${primaryButtonClass}`}>
              {t('projects.newProject')}
            </Link>
          </div>
        )}

        {!isLoading && !isError && projects.length > 0 && (
          <ul className="grid gap-4 sm:grid-cols-2">
            {projects.map((project) => (
              <li key={project.id} className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm">
                <Link to={`/projects/${project.id}`} className="block">
                  <h2 className="font-medium text-gray-900">{project.name}</h2>
                  {project.description && (
                    <p className="mt-1 line-clamp-2 text-sm text-gray-600">{project.description}</p>
                  )}
                </Link>
                <div className="mt-3 flex items-center justify-between">
                  <span className="rounded-full bg-purple-100 px-2 py-0.5 text-xs font-medium text-purple-700">
                    {t(`roles.${project.myRole}`)}
                  </span>
                  <Link
                    to={`/projects/${project.id}/settings/members`}
                    className="text-xs text-purple-600 hover:underline"
                  >
                    {t('projects.members')}
                  </Link>
                </div>
                {project.archived && (
                  <span className="mt-2 inline-block rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-500">
                    {t('projects.archived')}
                  </span>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}
