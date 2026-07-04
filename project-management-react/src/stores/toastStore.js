import { create } from 'zustand'

let nextId = 0

// Глобальные тосты для событий, которые не привязаны к конкретной форме/секции страницы
// (например, "сессия истекла" — это не забота конкретной мутации, а состояние всего
// приложения, см. api/client.js и stores/useAuthBootstrap.js). Обычные ошибки мутаций
// по-прежнему показываются инлайн через getLocalizedErrorMessage рядом с формой — тосты
// не дублируют этот механизм, а закрывают то, что раньше происходило молча.
export const useToastStore = create((set) => ({
  toasts: [],

  pushToast: (message, variant = 'info') => {
    const id = nextId++
    set((state) => ({ toasts: [...state.toasts, { id, message, variant }] }))
    return id
  },

  dismissToast: (id) =>
    set((state) => ({ toasts: state.toasts.filter((toast) => toast.id !== id) })),
}))
