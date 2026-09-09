import { FolderX, Lock, TriangleAlert } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { getErrorCode, getLocalizedErrorMessage } from '../../lib/errorMessage'
import { primaryButtonClass } from '../ui/FormKit'
import { StatusScreen } from '../ui/StatusScreen'

// Два разных ответа сервера — два разных экрана (5.3). Раньше оба выглядели одинаково:
// хедер, пустой сайдбар и красная строчка внутри страницы, которая всё равно пыталась
// рисоваться. Между тем разница принципиальна: в одном случае ищут опечатку в ссылке, в
// другом — просят коллегу добавить в проект.
const PROJECT_ERROR_SCREENS = {
  PROJECT_NOT_FOUND: { icon: FolderX, title: 'notFound.projectTitle', description: 'notFound.projectDescription' },
  NOT_A_PROJECT_MEMBER: { icon: Lock, title: 'notFound.noAccessTitle', description: 'notFound.noAccessDescription' },
}

/**
 * Проект по адресу не открылся. Показывается вместо всей страницы проекта, а не поверх
 * неё: разделы проекта, которого нет, — навигация в никуда.
 */
export function ProjectAccessNotice({ error }) {
  const { t } = useTranslation()
  const screen = PROJECT_ERROR_SCREENS[getErrorCode(error)]

  return (
    <StatusScreen
      // Незнакомый код (сеть, 500) — это не «проекта нет», и выдавать его за это нельзя:
      // человек пошёл бы искать ошибку в адресе вместо того, чтобы повторить попытку.
      icon={screen?.icon ?? TriangleAlert}
      title={screen ? t(screen.title) : t('notFound.projectErrorTitle')}
      description={screen ? t(screen.description) : getLocalizedErrorMessage(error, t)}
    >
      <Link to="/projects" className={primaryButtonClass}>
        {t('notFound.toProjects')}
      </Link>
    </StatusScreen>
  )
}
