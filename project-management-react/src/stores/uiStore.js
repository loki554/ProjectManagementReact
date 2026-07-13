import { create } from 'zustand'
import { persist } from 'zustand/middleware'

// Класс .dark на <html> — единственное, что переключает тему (см. @custom-variant
// dark в index.css). Применяем его императивно, а не через React-эффект, чтобы
// смена темы не требовала перезагрузки страницы и не мигала светлым при рехайдрации.
function applyTheme(theme) {
  document.documentElement.classList.toggle('dark', theme === 'dark')
}

// Настройки интерфейса, которые должны переживать перезагрузку страницы,
// но не имеют отношения к сессии (в отличие от authStore).
export const useUiStore = create(
  persist(
    (set, get) => ({
      projectSidebarCollapsed: false,
      theme: 'light',

      toggleProjectSidebar: () =>
        set((state) => ({ projectSidebarCollapsed: !state.projectSidebarCollapsed })),

      toggleTheme: () => {
        const theme = get().theme === 'dark' ? 'light' : 'dark'
        applyTheme(theme)
        set({ theme })
      },
    }),
    {
      name: 'pmtracker-ui',
      onRehydrateStorage: () => (state) => {
        if (state) applyTheme(state.theme)
      },
    },
  ),
)

// Первый визит (ничего ещё не в localStorage) — onRehydrateStorage всё равно
// сработает синхронно с дефолтным стейтом, но применяем и здесь на случай,
// если рехайдрация когда-нибудь станет асинхронной.
applyTheme(useUiStore.getState().theme)
