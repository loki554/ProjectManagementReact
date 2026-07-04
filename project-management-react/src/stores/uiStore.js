import { create } from 'zustand'
import { persist } from 'zustand/middleware'

// Настройки интерфейса, которые должны переживать перезагрузку страницы,
// но не имеют отношения к сессии (в отличие от authStore).
export const useUiStore = create(
  persist(
    (set) => ({
      projectSidebarCollapsed: false,

      toggleProjectSidebar: () =>
        set((state) => ({ projectSidebarCollapsed: !state.projectSidebarCollapsed })),
    }),
    {
      name: 'pmtracker-ui',
    },
  ),
)
