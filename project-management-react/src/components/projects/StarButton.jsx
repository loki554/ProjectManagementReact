import { Star } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useProjectStar, useSetProjectStarred } from '../../api/projectsQueries'
import { secondaryButtonClass } from '../../components/ui/FormKit'

// GitHub-стиль: тумблер "звезды" текущего пользователя + общий счётчик проекта.
export function StarButton({ projectId }) {
  const { t } = useTranslation()
  const { data: star } = useProjectStar(projectId)
  const setStarred = useSetProjectStarred(projectId)

  if (!star) {
    return null
  }

  return (
    <button
      type="button"
      onClick={() => setStarred.mutate(!star.starredByMe)}
      disabled={setStarred.isPending}
      className={`${secondaryButtonClass} flex w-full items-center justify-center gap-2`}
    >
      <Star
        className={`h-4 w-4 ${star.starredByMe ? 'fill-amber-400 text-amber-400' : ''}`}
        aria-hidden="true"
      />
      {star.starredByMe ? t('projectStar.starred') : t('projectStar.star')}
      <span className="rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600">
        {star.starCount}
      </span>
    </button>
  )
}
