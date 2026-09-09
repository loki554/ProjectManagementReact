import { Archive, Pencil } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router-dom'
import { useProjectBySlug, useProjectMembers, useSetProjectArchived } from '../../api/projectsQueries'
import { ActivityFeed } from '../../components/projects/ActivityFeed'
import { ProjectNotificationsCard } from '../../components/projects/ProjectNotificationsCard'
import { StarButton } from '../../components/projects/StarButton'
import { secondaryButtonClass } from '../../components/ui/FormKit'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { useAuthenticatedImage } from '../../lib/useAuthenticatedImage'

// Отдельный компонент, потому что useAuthenticatedImage — хук, и вызывать его
// в цикле по участникам внутри страницы нельзя.
function MemberAvatar({ member }) {
  const avatarUrl = useAuthenticatedImage(member.avatarUrl)
  const fullName = `${member.lastName} ${member.firstName}`

  return (
    <div
      title={fullName}
      className="flex h-10 w-10 items-center justify-center overflow-hidden rounded-full bg-gray-200 text-xs font-medium text-gray-600 dark:bg-gray-700 dark:text-gray-300"
    >
      {avatarUrl ? (
        <img src={avatarUrl} alt={fullName} className="h-full w-full object-cover" />
      ) : (
        <span aria-hidden="true">
          {(member.lastName?.[0] ?? '') + (member.firstName?.[0] ?? '')}
        </span>
      )}
    </div>
  )
}

export function ProjectOverviewPage() {
  const { t } = useTranslation()
  const { projectSlug } = useParams()

  const { data: project, isLoading, isError, error } = useProjectBySlug(projectSlug)
  const { data: members } = useProjectMembers(project?.id)
  const previewImageUrl = useAuthenticatedImage(project?.previewImageUrl)
  const setArchived = useSetProjectArchived(project?.id)

  if (isLoading) {
    return <p className="px-4 py-8 text-gray-500 dark:text-gray-400">{t('projectOverview.loading')}</p>
  }
  if (isError) {
    return <p className="px-4 py-8 text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(error, t)}</p>
  }
  if (!project) {
    return null
  }

  return (
    <div className="mx-auto max-w-7xl px-4 py-8">
      {/* Полоса на всю ширину обзора, а не бейдж в углу: архивный проект открывается по
          старой ссылке из письма или закладки, и человек должен понять, почему у него
          пропали все кнопки, раньше, чем начнёт их искать (4.14). */}
      {project.archived && (
        <div className="mb-6 flex flex-wrap items-center justify-between gap-3 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900 dark:border-amber-700 dark:bg-amber-950/40 dark:text-amber-200">
          <span className="flex items-center gap-2">
            <Archive className="h-4 w-4 shrink-0" aria-hidden="true" />
            {t('projects.archivedBanner')}
          </span>
          {project.myRole === 'OWNER' && (
            <button
              type="button"
              onClick={() => setArchived.mutate(false)}
              disabled={setArchived.isPending}
              className={secondaryButtonClass}
            >
              {setArchived.isPending ? t('projects.unarchiving') : t('projects.unarchive')}
            </button>
          )}
        </div>
      )}
      {setArchived.isError && (
        <p className="mb-4 text-sm text-red-600 dark:text-red-400">
          {getLocalizedErrorMessage(setArchived.error, t)}
        </p>
      )}

      <div className="flex flex-col gap-6 lg:flex-row">
        <div className="min-w-0 flex-1 space-y-6">
          <div className="flex gap-4 rounded-lg border border-gray-200 bg-white p-6 dark:border-gray-700 dark:bg-gray-800">
            <div className="h-20 w-20 shrink-0 overflow-hidden rounded-lg bg-gray-100 dark:bg-gray-700">
              {previewImageUrl && (
                <img src={previewImageUrl} alt="" className="h-full w-full object-cover" />
              )}
            </div>
            <div className="min-w-0">
              <h1 className="flex flex-wrap items-center gap-2 text-2xl font-semibold text-gray-900 dark:text-gray-100">
                <span className="min-w-0 break-words">{project.name}</span>
                {project.archived && (
                  <span className="rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-500 dark:bg-gray-700 dark:text-gray-400">
                    {t('projects.archived')}
                  </span>
                )}
              </h1>
              {project.description ? (
                <p className="mt-2 text-sm whitespace-pre-wrap text-gray-600 dark:text-gray-400">
                  {project.description}
                </p>
              ) : (
                <p className="mt-2 text-sm text-gray-400 dark:text-gray-500">{t('projectOverview.noDescription')}</p>
              )}
            </div>
          </div>

          <ActivityFeed projectId={project.id} projectSlug={projectSlug} />
        </div>

        <aside className="w-full shrink-0 space-y-4 lg:w-72">
          <StarButton projectId={project.id} />

          {/* И в архиве тоже: архив запрещает менять проект, а не выключать себе письма
              про него (4.16). */}
          <ProjectNotificationsCard projectId={project.id} />

          {/* Косметическое скрытие — PATCH на бэкенде в любом случае OWNER-only. В архиве
              настройки не правятся вовсе (409 PROJECT_ARCHIVED), поэтому кнопки нет:
              вернуть проект из архива предлагает полоса сверху. */}
          {project.myRole === 'OWNER' && !project.archived && (
            <Link
              to="settings/edit"
              className={`${secondaryButtonClass} flex w-full items-center justify-center gap-2`}
            >
              <Pencil className="h-4 w-4" aria-hidden="true" />
              {t('projectOverview.edit')}
            </Link>
          )}

          <div className="rounded-lg border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-800">
            <h2 className="text-sm font-semibold text-gray-900 dark:text-gray-100">{t('projects.members')}</h2>
            <div className="mt-3 flex flex-wrap gap-2">
              {members?.map((member) => (
                <MemberAvatar key={member.userId} member={member} />
              ))}
            </div>
            <Link
              to="settings/members"
              className="mt-3 block text-sm text-purple-600 hover:underline dark:text-purple-400"
            >
              {t('projectOverview.allMembers')}
            </Link>
          </div>
        </aside>
      </div>
    </div>
  )
}
