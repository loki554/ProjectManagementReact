import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App.jsx'
import { ErrorBoundary } from './components/errors/ErrorBoundary'
import { AppErrorScreen } from './components/errors/ErrorFallback'
import './i18n'
import './index.css'

const queryClient = new QueryClient()

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
