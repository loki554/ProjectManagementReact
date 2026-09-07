import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { RegisterPage } from './RegisterPage'
import { renderWithProviders } from '../../test/renderWithProviders'

vi.mock('../../api/authApi', () => ({ register: vi.fn() }))

const { register } = await import('../../api/authApi')

const VALID = {
  email: 'user@example.com',
  username: 'ivanov',
  password: 'long-enough-password',
  lastName: 'Иванов',
  firstName: 'Иван',
}

async function fill(user, overrides = {}) {
  const values = { ...VALID, patronymic: '', ...overrides }
  // Пустые значения просто не набираем: поле и так пустое, а user.type('') падает.
  for (const [label, value] of [
    ['Email', values.email],
    ['Username', values.username],
    ['Password', values.password],
    ['Last name', values.lastName],
    ['First name', values.firstName],
    ['Middle name (optional)', values.patronymic],
  ]) {
    if (value) {
      await user.type(screen.getByLabelText(label), value)
    }
  }
  await user.click(screen.getByRole('button', { name: 'Sign up' }))
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('RegisterPage', () => {
  // Граница «минимум 8» продублирована на бэкенде (@Size(min = 8) в RegisterRequest) —
  // здесь она нужна, чтобы пользователь узнал о ней до отправки, а не из 400-го ответа.
  it('пароль короче восьми символов не уходит на сервер', async () => {
    const user = userEvent.setup()
    renderWithProviders(<RegisterPage />)

    await fill(user, { password: 'short7' })

    expect(await screen.findByText('Password must be at least 8 characters')).toBeInTheDocument()
    expect(register).not.toHaveBeenCalled()
  })

  it('ровно восемь символов — уже допустимо', async () => {
    const user = userEvent.setup()
    register.mockResolvedValue({})
    renderWithProviders(<RegisterPage />)

    await fill(user, { password: '12345678' })

    await waitFor(() => expect(register).toHaveBeenCalled())
    expect(register.mock.calls[0][0]).toMatchObject({ password: '12345678' })
  })

  it('фамилия и имя обязательны, отчество — нет', async () => {
    const user = userEvent.setup()
    register.mockResolvedValue({})
    renderWithProviders(<RegisterPage />)

    await fill(user)

    await waitFor(() => expect(register).toHaveBeenCalled())
    expect(register.mock.calls[0][0]).toMatchObject({
      email: VALID.email,
      username: VALID.username,
      lastName: 'Иванов',
      firstName: 'Иван',
      patronymic: '',
    })
  })

  it('пустые фамилия и имя показывают ошибку у своих полей', async () => {
    const user = userEvent.setup()
    renderWithProviders(<RegisterPage />)

    await fill(user, { lastName: '', firstName: '' })

    expect(await screen.findAllByText('This field is required')).toHaveLength(2)
    expect(register).not.toHaveBeenCalled()
  })

  /**
   * Формат никнейма продублирован на бэкенде (@Pattern в RegisterRequest и CHECK в V28) —
   * здесь он нужен, чтобы человек узнал о нём до отправки. На нём же держится разбор
   * @упоминаний: никнейм с точкой или пробелом означал бы человека, которого нельзя позвать.
   */
  it('никнейм не по формату не уходит на сервер', async () => {
    const user = userEvent.setup()
    renderWithProviders(<RegisterPage />)

    await fill(user, { username: 'ivan.ov' })

    expect(await screen.findByText('3–30 characters: latin letters, digits, _ and -')).toBeInTheDocument()
    expect(register).not.toHaveBeenCalled()
  })

  it('никнейм обязателен', async () => {
    const user = userEvent.setup()
    renderWithProviders(<RegisterPage />)

    await fill(user, { username: '' })

    expect(await screen.findByText('This field is required')).toBeInTheDocument()
    expect(register).not.toHaveBeenCalled()
  })

  /**
   * После успешной регистрации форма подменяется экраном «проверьте почту» — и это не
   * косметика: с версии 1.10 бэкенд отвечает одинаково и на свободный, и на занятый адрес,
   * так что единственный оставшийся источник правды для пользователя — письмо.
   */
  it('после отправки показывает экран «проверьте почту» вместо формы', async () => {
    const user = userEvent.setup()
    register.mockResolvedValue({})
    renderWithProviders(<RegisterPage />)

    await fill(user)

    expect(await screen.findByText('Check your email')).toBeInTheDocument()
    expect(screen.queryByLabelText('Password')).not.toBeInTheDocument()
  })

  it('ошибку сервера показывает переводом по коду', async () => {
    const user = userEvent.setup()
    register.mockRejectedValue({ response: { status: 429, data: { error: 'TOO_MANY_REQUESTS' }, headers: {} } })
    renderWithProviders(<RegisterPage />)

    await fill(user)

    expect(await screen.findByText('Too many attempts. Please try again later.')).toBeInTheDocument()
  })
})
