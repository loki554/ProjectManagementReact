import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { ConfirmDialogHost } from './ConfirmDialog'
import { askConfirmation, confirmAction, resolveConfirmation, useConfirmStore } from '../../stores/confirmStore'

// Вопрос переживает размонтирование компонента, который его задал (в этом и смысл общего
// стора), поэтому оставленный незакрытым диалог утёк бы в следующий тест.
afterEach(() => {
  if (useConfirmStore.getState().request) {
    act(() => resolveConfirmation(null))
  }
})

/** Задать вопрос так, как это делает страница: из обработчика, без await на месте. */
function ask(request) {
  let answer
  act(() => {
    answer = request()
  })
  return answer
}

describe('ConfirmDialogHost', () => {
  it('пока никто не спрашивает, в разметке ничего нет', () => {
    render(<ConfirmDialogHost />)

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('показывает вопрос, подробности и кнопки — и отвечает «да» по нажатию', async () => {
    const user = userEvent.setup()
    render(<ConfirmDialogHost />)

    const answer = ask(() =>
      confirmAction({
        title: 'Delete this tag?',
        body: 'It will be removed from any tasks using it.',
        confirmLabel: 'Delete',
      }),
    )

    expect(screen.getByRole('heading', { name: 'Delete this tag?' })).toBeInTheDocument()
    expect(screen.getByText('It will be removed from any tasks using it.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Delete' }))

    expect(await answer).toBe(true)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('кнопка отмены отвечает «нет», а не просто закрывает окно', async () => {
    const user = userEvent.setup()
    render(<ConfirmDialogHost />)

    const answer = ask(() => confirmAction({ title: 'Delete this task?', confirmLabel: 'Delete' }))
    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(await answer).toBe(false)
  })

  it('Esc — это тоже «нет»: браузер закрыл бы диалог мимо стора, и вызов завис бы навсегда', async () => {
    render(<ConfirmDialogHost />)

    const answer = ask(() => confirmAction({ title: 'Delete this task?', confirmLabel: 'Delete' }))
    // Настоящий Esc внутри <dialog> обрабатывает браузер и шлёт событие cancel; jsdom
    // модальность не реализует вовсе, поэтому шлём событие сами. Живой Esc проверяется e2e.
    fireEvent(screen.getByRole('dialog'), new Event('cancel', { bubbles: false, cancelable: true }))

    expect(await answer).toBe(false)
  })

  it('под фокусом — отмена: первое, что нажмут не глядя, ничего не удаляет', () => {
    render(<ConfirmDialogHost />)

    ask(() => confirmAction({ title: 'Delete this task?', confirmLabel: 'Delete' }))

    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus()
  })

  it('вариантов может быть больше двух, и отказ отличается от любого из них', async () => {
    const user = userEvent.setup()
    render(<ConfirmDialogHost />)

    const answer = ask(() =>
      askConfirmation({
        title: 'Complete this sprint?',
        body: '2 tasks are still open',
        choices: [
          { id: 'backlog', label: 'Send to the backlog', tone: 'primary' },
          { id: 'move', label: 'Move to "Sprint 2"', tone: 'primary' },
        ],
      }),
    )

    await user.click(screen.getByRole('button', { name: 'Move to "Sprint 2"' }))

    expect(await answer).toBe('move')
  })

  it('отказ от вопроса с тремя ответами — null, а не один из вариантов', async () => {
    const user = userEvent.setup()
    render(<ConfirmDialogHost />)

    const answer = ask(() =>
      askConfirmation({
        title: 'Complete this sprint?',
        choices: [{ id: 'backlog', label: 'Send to the backlog' }],
      }),
    )

    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(await answer).toBeNull()
  })

  it('новый вопрос закрывает предыдущий отказом — иначе тот вызов ждал бы ответа вечно', async () => {
    render(<ConfirmDialogHost />)

    const first = ask(() => confirmAction({ title: 'Delete this comment?', confirmLabel: 'Delete' }))
    const second = ask(() => confirmAction({ title: 'Delete this attachment?', confirmLabel: 'Delete' }))

    expect(await first).toBe(false)
    expect(screen.getByRole('heading', { name: 'Delete this attachment?' })).toBeInTheDocument()

    act(() => resolveConfirmation('confirm'))
    expect(await second).toBe(true)
  })
})
