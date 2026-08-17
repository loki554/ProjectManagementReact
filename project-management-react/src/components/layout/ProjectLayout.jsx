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

  // Высота фиксирована по вьюпорту, скроллится не body, а <main>. Это даёт страницам
  // внутри проекта (список задач, канбан) полноценную область известной высоты: можно
  // сделать залипающую шапку таблицы и колонки канбана со своим скроллом, а хедер и
  // сайдбар при этом всегда остаются на экране.
  return (
    <div className="flex h-svh flex-col overflow-hidden">
      <AppHeader />
      <div className="flex min-h-0 flex-1">
        <ProjectSidebar project={project} />
        <main className="min-h-0 min-w-0 flex-1 overflow-y-auto">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
