import { useEffect } from 'react'
import { useToastStore } from '../../stores/toastStore'

const AUTO_DISMISS_MS = 6000

const VARIANT_CLASSES = {
  info: 'bg-gray-900 text-white',
  error: 'bg-red-600 text-white',
}

function Toast({ toast, onDismiss }) {
  useEffect(() => {
    const timer = setTimeout(onDismiss, AUTO_DISMISS_MS)
    return () => clearTimeout(timer)
  }, [onDismiss])

  return (
    <div
      role="status"
      className={`pointer-events-auto flex max-w-md items-center gap-3 rounded-md px-4 py-3 text-sm shadow-lg ${
        VARIANT_CLASSES[toast.variant] ?? VARIANT_CLASSES.info
      }`}
    >
      <span className="flex-1">{toast.message}</span>
      <button type="button" onClick={onDismiss} className="text-white/70 hover:text-white">
        ✕
      </button>
    </div>
  )
}

// Смонтирован один раз в App.jsx, вне <Routes> — тосты общеприкладного уровня
// (например, "сессия истекла", см. stores/toastStore.js) должны быть видны независимо
// от текущей страницы и пережить последующий редирект на /login.
export function ToastContainer() {
  const toasts = useToastStore((state) => state.toasts)
  const dismissToast = useToastStore((state) => state.dismissToast)

  if (toasts.length === 0) {
    return null
  }

  return (
    <div className="pointer-events-none fixed inset-x-0 bottom-4 z-50 flex flex-col items-center gap-2 px-4">
      {toasts.map((toast) => (
        <Toast key={toast.id} toast={toast} onDismiss={() => dismissToast(toast.id)} />
      ))}
    </div>
  )
}
