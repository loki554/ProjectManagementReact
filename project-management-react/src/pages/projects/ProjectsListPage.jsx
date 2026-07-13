import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { useProjects } from '../../api/projectsQueries'
import { AppHeader } from '../../components/layout/AppHeader'
import { ProjectCard } from '../../components/projects/ProjectCard'
import { primaryButtonClass } from '../../components/ui/FormKit'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { MyActiveTasksSection } from './MyActiveTasksSection'

export function ProjectsListPage() {
  const { t } = useTranslation()
  const { data: projects, isLoading, isError, error } = useProjects()

  return (
    <div className="min-h-svh">
      <AppHeader />

      <div className="mx-auto max-w-4xl px-4 py-8">
        <div className="mb-6 flex items-center justify-between">
          <h1 className="text-2xl font-semibold text-gray-900 dark:text-gray-100">{t('projects.title')}</h1>
          <Link to="/projects/new" className={primaryButtonClass}>
            {t('projects.newProject')}
          </Link>
        </div>

        {isLoading && <p className="text-gray-500 dark:text-gray-400">{t('projects.loading')}</p>}
        {isError && <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(error, t)}</p>}

        {!isLoading && !isError && projects.length === 0 && (
          <div className="flex flex-col items-center justify-center rounded-lg border border-dashed border-gray-300 bg-white py-16 text-center dark:border-gray-600 dark:bg-gray-800">
            <p className="text-gray-600 dark:text-gray-400">{t('projects.empty')}</p>
            <Link to="/projects/new" className={`mt-4 ${primaryButtonClass}`}>
              {t('projects.newProject')}
            </Link>
          </div>
        )}

        {!isLoading && !isError && projects.length > 0 && (
          <ul className="grid max-h-[420px] gap-4 overflow-y-auto pr-1 sm:grid-cols-2">
            {projects.map((project) => (
              <ProjectCard key={project.id} project={project} />
            ))}
          </ul>
        )}

        <hr className="my-8 border-gray-200 border-2 rounded-2xl dark:border-gray-700" />

        <h2 className="mb-4 text-xl font-semibold text-gray-900 dark:text-gray-100">{t('home.myActiveTasksTitle')}</h2>
        <MyActiveTasksSection />
      </div>
    </div>
  )
}
