import { screen } from '@testing-library/react'
import axios from 'axios'
import { describe, expect, it } from 'vitest'
import { NotFoundPage } from './NotFoundPage'
import { ProjectAccessNotice } from '../components/layout/ProjectAccessNotice'
import { TaskErrorNotice } from '../components/tasks/TaskErrorNotice'
import { renderWithProviders } from '../test/renderWithProviders'

/** Ошибка ровно того вида, в каком её отдаёт axios: код лежит в теле ответа. */
function serverError(code, status) {
  return new axios.AxiosError('Request failed', 'ERR_BAD_REQUEST', {}, {}, {
    status,
    data: { error: code },
    headers: {},
  })
}

describe('NotFoundPage', () => {
  it('говорит, что страницы нет, и показывает запрошенный адрес целиком', () => {
    renderWithProviders(<NotFoundPage />, { route: '/projects/typo/tasks/999' })

    expect(screen.getByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
    // Адрес — половина ответа: опечатку в номере задачи иначе не увидеть.
    expect(screen.getByText('/projects/typo/tasks/999')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to projects' })).toHaveAttribute('href', '/projects')
  })

  it('строка запроса тоже видна — в ссылках на список задач она несёт фильтры', () => {
    renderWithProviders(<NotFoundPage />, { route: '/projects/x/tasks?status=DONE' })

    expect(screen.getByText('/projects/x/tasks?status=DONE')).toBeInTheDocument()
  })
})

describe('ProjectAccessNotice', () => {
  it('«проекта нет» — это про адрес', () => {
    renderWithProviders(<ProjectAccessNotice error={serverError('PROJECT_NOT_FOUND', 404)} />)

    expect(screen.getByRole('heading', { name: 'Project not found' })).toBeInTheDocument()
    expect(screen.getByText(/Check the address/)).toBeInTheDocument()
  })

  it('«не пускают» — это про людей, а не про адрес', () => {
    renderWithProviders(<ProjectAccessNotice error={serverError('NOT_A_PROJECT_MEMBER', 403)} />)

    expect(screen.getByRole('heading', { name: 'No access to this project' })).toBeInTheDocument()
    expect(screen.getByText(/Ask someone from its team/)).toBeInTheDocument()
  })

  it('незнакомая ошибка не выдаётся за «проекта нет»: показывается она сама', () => {
    renderWithProviders(<ProjectAccessNotice error={serverError('INTERNAL_ERROR', 500)} />)

    expect(screen.getByRole('heading', { name: 'Could not open the project' })).toBeInTheDocument()
    expect(screen.getByText('Something went wrong on the server, please try again')).toBeInTheDocument()
  })
})

describe('TaskErrorNotice', () => {
  it('ненайденная задача — экран с обеими причинами и ссылкой в корзину', () => {
    renderWithProviders(<TaskErrorNotice error={serverError('TASK_NOT_FOUND', 404)} projectSlug="alpha" />)

    expect(screen.getByRole('heading', { name: 'Task not found' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open the trash' })).toHaveAttribute('href', '/projects/alpha/trash')
    expect(screen.getByRole('link', { name: 'Go to the board' })).toHaveAttribute('href', '/projects/alpha/board')
  })

  it('любая другая ошибка остаётся обычной строкой ошибки, а не «задача исчезла»', () => {
    renderWithProviders(<TaskErrorNotice error={serverError('INTERNAL_ERROR', 500)} projectSlug="alpha" />)

    expect(screen.queryByRole('heading', { name: 'Task not found' })).not.toBeInTheDocument()
    expect(screen.getByText('Something went wrong on the server, please try again')).toBeInTheDocument()
  })
})
