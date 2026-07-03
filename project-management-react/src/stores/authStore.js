import { create } from 'zustand'
import { persist } from 'zustand/middleware'

// accessToken умышленно НЕ персистится в localStorage — живёт только в памяти
// вкладки. При перезагрузке страницы память обнуляется, и сессия восстанавливается
// через refreshToken (см. useAuthBootstrap) — так access-токен меньше времени
// проводит в постоянном хранилище, доступном через XSS.
export const useAuthStore = create(
  persist(
    (set) => ({
      accessToken: null,
      refreshToken: null,
      user: null,

      setSession: ({ accessToken, refreshToken, user }) =>
        set((state) => ({
          accessToken,
          refreshToken: refreshToken ?? state.refreshToken,
          user: user ?? state.user,
        })),

      clearSession: () => set({ accessToken: null, refreshToken: null, user: null }),
    }),
    {
      name: 'pmtracker-auth',
      partialize: (state) => ({ refreshToken: state.refreshToken, user: state.user }),
    },
  ),
)
