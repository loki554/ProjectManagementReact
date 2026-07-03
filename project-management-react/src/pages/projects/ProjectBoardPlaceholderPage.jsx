import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router-dom'
import { useProject } from '../../api/projectsQueries'
import { AppHeader } from '../../components/layout/AppHeader'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'

// Канбан-доска — Phase 5. Страница существует уже сейчас, чтобы /projects/:id
// (редирект сюда) и переход по карточке проекта из списка вели куда-то реальное,
// а не в несуществующий роут.
export function ProjectBoardPlaceholderPage() {
  const { t } = useTranslation()
  const { projectId } = useParams()
  const { data: project, isLoading, isError, error } = useProject(projectId)

  return (
    <div className="min-h-svh bg-gray-50">
      <AppHeader />

      <div className="mx-auto max-w-4xl px-4 py-8">
        <Link to="/projects" className="text-sm text-purple-600 hover:underline">
          {t('projects.backToList')}
        </Link>

        {isError && <p className="mt-4 text-sm text-red-600">{getLocalizedErrorMessage(error, t)}</p>}

        {!isError && (
          <>
            <h1 className="mt-4 text-2xl font-semibold text-gray-900">
              {isLoading ? t('projects.loading') : project.name}
            </h1>

            <div className="mt-6 rounded-lg border border-dashed border-gray-300 bg-white p-12 text-center text-gray-500">
              {t('projects.boardComingSoon')}
            </div>

            <Link
              to={`/projects/${projectId}/settings/members`}
              className="mt-4 inline-block text-sm text-purple-600 hover:underline"
            >
              {t('projects.members')}
            </Link>
          </>
        )}
      </div>
    </div>
  )
}
