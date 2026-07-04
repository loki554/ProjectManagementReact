import { Outlet, useParams } from 'react-router-dom'
import { useProjectBySlug } from '../../api/projectsQueries'
import { AppHeader } from './AppHeader'
import { ProjectSidebar } from './ProjectSidebar'

// Общий каркас всех страниц внутри проекта: сверху хедер, слева сайдбар с
// навигацией, справа контент конкретной страницы (Outlet). Дочерние страницы
// получают проект тем же useProjectBySlug — react-query дедуплицирует запрос
// по одинаковому queryKey, поэтому передавать проект через Outlet context не нужно.
export function ProjectLayout() {
  const { projectSlug } = useParams()
  const { data: project } = useProjectBySlug(projectSlug)

  return (
    <div className="flex min-h-svh flex-col">
      <AppHeader />
      <div className="flex flex-1">
        <ProjectSidebar project={project} />
        <main className="min-w-0 flex-1">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
