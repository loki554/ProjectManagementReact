import { create } from 'zustand'
import { persist } from 'zustand/middleware'

const AUTH_STORAGE_KEY = 'pmtracker-auth'

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

      // Отдельно от setSession: используется после PATCH /users/me и загрузки аватарки,
      // где обновляется только профиль, а accessToken/refreshToken трогать не нужно —
      // setSession перезаписал бы accessToken на undefined, если его не передать.
      updateUser: (user) => set({ user }),

      clearSession: () => set({ accessToken: null, refreshToken: null, user: null }),
    }),
    {
      name: AUTH_STORAGE_KEY,
      partialize: (state) => ({ refreshToken: state.refreshToken, user: state.user }),
    },
  ),
)

// refreshToken лежит в localStorage и потому общий для всех вкладок, а состояние стора —
// нет: zustand между вкладками не синхронизируется. Без этого вторая вкладка держала бы
// в памяти токен, который первая уже ротировала, и рано или поздно предъявила бы его
// бэкенду — а тот считает повторное использование ротированного токена кражей и гасит
// все сессии пользователя (см. AuthService.refresh, IMPROVEMENTS 1.6). Основную защиту
// от этого даёт перечитывание токена прямо перед запросом (см. client.js), здесь —
// вторая половина: держать сам стор в актуальном состоянии, чтобы на устаревший токен
// не наткнулось что-нибудь ещё (например, кнопка «выйти», отзывающая уже мёртвый токен
// вместо живого).
//
// storage-событие приходит только в ДРУГИЕ вкладки, то есть ровно туда, где стор устарел;
// вкладка, сделавшая запись, своего события не получает и лишний раз не дёргается.
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (event) => {
    if (event.key !== AUTH_STORAGE_KEY) {
      return
    }

    // Ключ вычистили целиком («очистить данные сайта», ручное удаление) — перечитывать
    // нечего, гасим сессию здесь же.
    if (event.newValue === null) {
      useAuthStore.getState().clearSession()
      return
    }

    // rehydrate() возвращает промис для асинхронных хранилищ и undefined для localStorage —
    // Promise.resolve приводит оба случая к одному виду.
    Promise.resolve(useAuthStore.persist.rehydrate()).then(() => {
      // В соседней вкладке разлогинились: refreshToken обнулён, а наш accessToken в памяти
      // иначе дожил бы до первого 401. Гасим сразу — ProtectedRoute смотрит именно на
      // accessToken и уведёт на /login, вместо того чтобы показывать хедер без имени.
      if (!useAuthStore.getState().refreshToken && useAuthStore.getState().accessToken) {
        useAuthStore.getState().clearSession()
      }
    })
  })
}
