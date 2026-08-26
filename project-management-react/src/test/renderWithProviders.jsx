import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'

/**
 * Страницы приложения не рендерятся в вакууме: формы шлют мутации через react-query, а
 * ссылки и переходы — через router. Провайдеры настоящие, а не заглушки: подменять их
 * значило бы тестировать не ту страницу, которая работает в проде.
 *
 * retry: false — единственное отличие от боевой конфигурации. По умолчанию react-query
 * повторяет неудачные запросы, и тест «форма показала ошибку сервера» ждал бы ретраев
 * несколько секунд вместо того, чтобы сразу увидеть результат.
 */
export function renderWithProviders(ui, { route = '/' } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}
