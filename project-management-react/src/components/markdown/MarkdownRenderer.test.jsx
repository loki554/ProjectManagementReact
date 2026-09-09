import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// Сломанный react-markdown вместо настоящего: проверяется не библиотека, а то, что её
// падение остаётся внутри компонента и не уносит страницу, на которой он стоит.
vi.mock('react-markdown', () => ({
  default: () => {
    throw new Error('markdown parser blew up')
  },
}))

const { MarkdownRenderer } = await import('./MarkdownRenderer')

beforeEach(() => {
  vi.spyOn(console, 'error').mockImplementation(() => {})
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('MarkdownRenderer при падении разметки', () => {
  it('показывает исходный текст, а не роняет всё вокруг', () => {
    render(
      <div>
        <p>соседний комментарий</p>
        <MarkdownRenderer>{'# Заголовок\n\nтекст комментария'}</MarkdownRenderer>
      </div>,
    )

    expect(screen.getByText(/Failed to render Markdown/)).toBeInTheDocument()
    expect(screen.getByText(/текст комментария/)).toBeInTheDocument()
    expect(screen.getByText('соседний комментарий')).toBeInTheDocument()
  })
})
