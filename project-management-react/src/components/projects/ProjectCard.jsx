import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'

export function ProjectCard({ project }) {
  const { t } = useTranslation()

  return (
    <li className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm">
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
        <div className="flex items-center gap-3">
          <Link
            to={`/projects/${project.id}/settings/tags`}
            className="text-xs text-purple-600 hover:underline"
          >
            {t('tags.navLabel')}
          </Link>
          <Link
            to={`/projects/${project.id}/settings/members`}
            className="text-xs text-purple-600 hover:underline"
          >
            {t('projects.members')}
          </Link>
        </div>
      </div>
      {project.archived && (
        <span className="mt-2 inline-block rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-500">
          {t('projects.archived')}
        </span>
      )}
    </li>
  )
}
