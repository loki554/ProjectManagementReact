import { BookOpen, ChevronsLeft, ChevronsRight, List, SquareKanban, Tag, Users } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { NavLink } from 'react-router-dom'
import { useAuthenticatedImage } from '../../lib/useAuthenticatedImage'
import { useUiStore } from '../../stores/uiStore'

// end у «Списка задач» — чтобы пункт не подсвечивался на странице конкретной задачи
// (tasks/:taskNumber), куда обычно приходят с канбана.
const NAV_ITEMS = [
  { to: 'tasks', icon: List, labelKey: 'projectSidebar.taskList', end: true },
  { to: 'board', icon: SquareKanban, labelKey: 'projectSidebar.kanban' },
  { to: 'settings/members', icon: Users, labelKey: 'projectSidebar.members' },
  { to: 'wiki', icon: BookOpen, labelKey: 'projectSidebar.wiki' },
  { to: 'settings/tags', icon: Tag, labelKey: 'projectSidebar.tags' },
]

export function ProjectSidebar({ project }) {
  const { t } = useTranslation()
  const collapsed = useUiStore((state) => state.projectSidebarCollapsed)
  const toggleSidebar = useUiStore((state) => state.toggleProjectSidebar)
  const previewImageUrl = useAuthenticatedImage(project?.previewImageUrl)

  return (
    <aside
      className={`flex shrink-0 flex-col border-r border-gray-200 bg-white transition-[width] duration-200 ${
        collapsed ? 'w-16' : 'w-64'
      }`}
    >
      <NavLink
        to="."
        end
        title={collapsed ? project?.name : undefined}
        className={({ isActive }) =>
          `flex items-center gap-3 border-b border-gray-200 p-3 ${
            collapsed ? 'justify-center' : ''
          } ${isActive ? 'bg-purple-50' : 'hover:bg-gray-50'}`
        }
      >
        <div className="h-10 w-10 shrink-0 overflow-hidden rounded-md bg-gray-100">
          {previewImageUrl && (
            <img src={previewImageUrl} alt="" className="h-full w-full object-cover" />
          )}
        </div>
        {!collapsed && (
          <span className="min-w-0 truncate text-sm font-semibold text-gray-900">
            {project?.name}
          </span>
        )}
      </NavLink>

      <nav className="flex-1 space-y-1 p-2">
        {NAV_ITEMS.map(({ to, icon: Icon, labelKey, end }) => (
          <NavLink
            key={to}
            to={to}
            end={end}
            title={collapsed ? t(labelKey) : undefined}
            className={({ isActive }) =>
              `flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium ${
                collapsed ? 'justify-center px-0' : ''
              } ${isActive ? 'bg-purple-50 text-purple-700' : 'text-gray-700 hover:bg-gray-50'}`
            }
          >
            <Icon className="h-5 w-5 shrink-0" aria-hidden="true" />
            {!collapsed && <span className="truncate">{t(labelKey)}</span>}
          </NavLink>
        ))}
      </nav>

      <button
        type="button"
        onClick={toggleSidebar}
        title={collapsed ? t('projectSidebar.expand') : undefined}
        className={`flex items-center gap-3 border-t border-gray-200 px-3 py-3 text-sm font-medium text-gray-600 hover:bg-gray-50 ${
          collapsed ? 'justify-center px-0' : ''
        }`}
      >
        {collapsed ? (
          <ChevronsRight className="h-5 w-5 shrink-0" aria-hidden="true" />
        ) : (
          <ChevronsLeft className="h-5 w-5 shrink-0" aria-hidden="true" />
        )}
        {!collapsed && <span>{t('projectSidebar.collapse')}</span>}
      </button>
    </aside>
  )
}
