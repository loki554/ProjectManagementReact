import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App.jsx'
import { ErrorBoundary } from './components/errors/ErrorBoundary'
import { AppErrorScreen } from './components/errors/ErrorFallback'
import { loadDetectedLanguage } from './i18n'
import './index.css'

const queryClient = new QueryClient()

// Словарь текущего языка — до первого рендера (5.4): он теперь отдельный файл, и без
// этого ожидания первый кадр был бы на ключах вместо слов. Грузится он параллельно с основным
// файлом приложения, а не после него: import() вызывается до рендера, а не из компонента.
await loadDetectedLanguage()

createRoot(document.getElementById('root')).render(
  <StrictMode>
    {/* Корневая граница стоит снаружи провайдеров: ниже неё у реакта остаётся только один
        способ сообщить об ошибке — снести всё дерево и оставить белый экран (5.1). Поэтому сюда
        попадает и падение самих провайдеров, и всё, что маршрутная граница в App.jsx пропустила:
        тосты, поток живых обновлений, экран загрузки сессии. */}
    <ErrorBoundary name="root" fallback={({ error }) => <AppErrorScreen error={error} />}>
      <QueryClientProvider client={queryClient}>
        <App />
      </QueryClientProvider>
    </ErrorBoundary>
  </StrictMode>,
)
