import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ErrorBoundary } from './ErrorBoundary'
import { AppErrorScreen, SectionErrorNotice } from './ErrorFallback'

// React печатает каждую пойманную ошибку в консоль сам, и в этом файле падение — предмет
// проверки, а не сбой: без заглушки вывод тестов состоит из ожидаемых стектрейсов.
beforeEach(() => {
  vi.spyOn(console, 'error').mockImplementation(() => {})
})

afterEach(() => {
  vi.restoreAllMocks()
})

function Boom({ explode }) {
  if (explode) {
    throw new Error('boom')
  }
  return <p>содержимое</p>
}

function fallback({ error, reset }) {
  return (
    <div>
      <p>запасной экран: {error.message}</p>
      <button type="button" onClick={reset}>
        снова
      </button>
    </div>
  )
}

describe('ErrorBoundary', () => {
  it('пока никто не падает, границы не видно', () => {
    render(
      <ErrorBoundary fallback={fallback}>
        <Boom explode={false} />
      </ErrorBoundary>,
    )

    expect(screen.getByText('содержимое')).toBeInTheDocument()
  })

  it('ошибка рендера превращается в запасной экран, а не в белый', () => {
    render(
      <ErrorBoundary fallback={fallback}>
        <Boom explode />
      </ErrorBoundary>,
    )

    expect(screen.getByText('запасной экран: boom')).toBeInTheDocument()
  })

  it('ошибка попадает в консоль вместе с именем границы — это единственный её след', () => {
    render(
      <ErrorBoundary name="kanban-board" fallback={fallback}>
        <Boom explode />
      </ErrorBoundary>,
    )

    expect(console.error).toHaveBeenCalledWith(
      '[ErrorBoundary: kanban-board]',
      expect.objectContaining({ message: 'boom' }),
      expect.anything(),
    )
  })

  it('перерисовка сама по себе ошибку не забывает, а reset — забывает', async () => {
    const user = userEvent.setup()
    function Harness() {
      const [explode, setExplode] = useState(true)
      return (
        <>
          <button type="button" onClick={() => setExplode(false)}>
            починить
          </button>
          <ErrorBoundary fallback={fallback}>
            <Boom explode={explode} />
          </ErrorBoundary>
        </>
      )
    }
    render(<Harness />)

    // Причину убрали, но граница всё ещё показывает запасной экран: иначе сломанный
    // компонент крутился бы в цикле «упал — перерисовался — упал».
    await user.click(screen.getByRole('button', { name: 'починить' }))
    expect(screen.getByText('запасной экран: boom')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'снова' }))
    expect(screen.getByText('содержимое')).toBeInTheDocument()
  })

  it('смена resetKeys возвращает детей без участия человека — так работает переход на другую страницу', async () => {
    const user = userEvent.setup()
    function Harness() {
      const [locationKey, setLocationKey] = useState('a')
      return (
        <>
          <button type="button" onClick={() => setLocationKey('b')}>
            перейти
          </button>
          <ErrorBoundary resetKeys={[locationKey]} fallback={fallback}>
            <Boom explode={locationKey === 'a'} />
          </ErrorBoundary>
        </>
      )
    }
    render(<Harness />)

    expect(screen.getByText('запасной экран: boom')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'перейти' }))
    expect(screen.getByText('содержимое')).toBeInTheDocument()
  })
})

describe('экраны ошибок', () => {
  it('корневой экран предлагает перезагрузку и уход к проектам, но не «попробовать снова»', () => {
    render(<AppErrorScreen error={new Error('boom')} />)

    expect(screen.getByRole('heading', { name: 'Something went wrong' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reload page' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to projects' })).toHaveAttribute('href', '/projects')
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument()
  })

  it('маршрутный экран добавляет «попробовать снова»', async () => {
    const user = userEvent.setup()
    const onRetry = vi.fn()
    render(<AppErrorScreen error={new Error('boom')} onRetry={onRetry} />)

    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(onRetry).toHaveBeenCalledOnce()
  })

  it('плашка секции говорит, что именно упало, и что делать дальше', () => {
    render(
      <SectionErrorNotice
        error={new Error('boom')}
        reset={vi.fn()}
        title="The board failed to render"
        hint="Open the task list instead."
      />,
    )

    expect(screen.getByRole('alert')).toHaveTextContent('The board failed to render')
    expect(screen.getByRole('alert')).toHaveTextContent('Open the task list instead.')
  })
})
