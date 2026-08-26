import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axios from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { LoginPage } from './LoginPage'
import { renderWithProviders } from '../../test/renderWithProviders'
import { useAuthStore } from '../../stores/authStore'

// vi.mock поднимается наверх файла, поэтому переменную для него нужно объявить через
// vi.hoisted — иначе к моменту создания мока её ещё не существует.
const { navigate } = vi.hoisted(() => ({ navigate: vi.fn() }))

vi.mock('react-router-dom', async (importOriginal) => ({
  ...(await importOriginal()),
  useNavigate: () => navigate,
}))

vi.mock('../../api/authApi', () => ({
  login: vi.fn(),
  resendVerification: vi.fn(),
}))

const { login, resendVerification } = await import('../../api/authApi')

/** Ошибка ровно того вида, в каком её отдаёт axios: код лежит в теле ответа. */
function serverError(code, status = 400) {
  return new axios.AxiosError('Request failed', 'ERR_BAD_REQUEST', {}, {}, {
    status,
    data: { error: code },
    headers: {},
  })
}

async function fillAndSubmit(user, { email, password }) {
  await user.type(screen.getByLabelText('Email'), email)
  await user.type(screen.getByLabelText('Password'), password)
  await user.click(screen.getByRole('button', { name: 'Sign in' }))
}

beforeEach(() => {
  vi.clearAllMocks()
  useAuthStore.setState({ accessToken: null, refreshToken: null, user: null })
})

describe('LoginPage', () => {
  describe('валидация формы (zod)', () => {
    it('пустая форма не уходит на сервер — обе ошибки показаны рядом с полями', async () => {
      const user = userEvent.setup()
      renderWithProviders(<LoginPage />)

      await user.click(screen.getByRole('button', { name: 'Sign in' }))

      expect(await screen.findAllByText('This field is required')).toHaveLength(2)
      expect(login).not.toHaveBeenCalled()
    })

    it('email без @ на сервер не уходит', async () => {
      const user = userEvent.setup()
      renderWithProviders(<LoginPage />)

      await fillAndSubmit(user, { email: 'not-an-email', password: 'secret123' })

      // Досюда zod даже не добирается: <input type="email"> отбивает такое значение
      // штатной проверкой браузера, и форма просто не отправляется. Проверяем то, что
      // важно пользователю и одинаково в любом браузере, — запрос не ушёл.
      expect(login).not.toHaveBeenCalled()
    })

    // А вот и причина, по которой правило продублировано в zod: HTML-проверка считает
    // адрес без доменной зоны допустимым (её регулярка требует только «что-то после @»),
    // а бэкенд такой адрес отвергнет. Здесь ловится именно эта щель.
    it('адрес без доменной зоны браузер пропускает, а схема — нет', async () => {
      const user = userEvent.setup()
      renderWithProviders(<LoginPage />)

      await fillAndSubmit(user, { email: 'user@example', password: 'secret123' })

      expect(await screen.findByText('Enter a valid email address')).toBeInTheDocument()
      expect(login).not.toHaveBeenCalled()
    })

    // Пароль здесь проверяется только на непустоту, и это осознанно: политика «минимум 8»
    // относится к регистрации. На входе она отвергала бы старые пароли и, что важнее,
    // подсказывала бы подбирающему, какие пароли можно не пробовать.
    it('короткий пароль на входе не отвергается — это не форма регистрации', async () => {
      const user = userEvent.setup()
      renderWithProviders(<LoginPage />)

      await fillAndSubmit(user, { email: 'user@example.com', password: 'x' })

      await waitFor(() => expect(login).toHaveBeenCalledWith('user@example.com', 'x'))
    })
  })

  describe('успешный вход', () => {
    it('кладёт сессию в стор и уводит на главную без возможности вернуться назад', async () => {
      const user = userEvent.setup()
      const session = { accessToken: 'access-1', refreshToken: 'refresh-1', user: { id: 'u1' } }
      login.mockResolvedValue(session)
      renderWithProviders(<LoginPage />)

      await fillAndSubmit(user, { email: 'user@example.com', password: 'secret123' })

      await waitFor(() => expect(useAuthStore.getState().accessToken).toBe('access-1'))
      // replace: true — чтобы «назад» из приложения не возвращало на форму входа.
      expect(navigate).toHaveBeenCalledWith('/', { replace: true })
    })
  })

  describe('ошибка от сервера', () => {
    it('показывает перевод по коду ошибки, а не сырое сообщение бэкенда', async () => {
      const user = userEvent.setup()
      login.mockRejectedValue(serverError('INVALID_CREDENTIALS', 401))
      renderWithProviders(<LoginPage />)

      await fillAndSubmit(user, { email: 'user@example.com', password: 'wrong-password' })

      expect(await screen.findByText('Invalid email or password')).toBeInTheDocument()
      expect(useAuthStore.getState().accessToken).toBeNull()
    })

    it('неизвестный код не показывает пользователю ключ перевода', async () => {
      const user = userEvent.setup()
      login.mockRejectedValue(serverError('SOME_BRAND_NEW_CODE', 400))
      renderWithProviders(<LoginPage />)

      await fillAndSubmit(user, { email: 'user@example.com', password: 'secret123' })

      expect(await screen.findByText('Something went wrong, please try again')).toBeInTheDocument()
    })

    /**
     * Единственная ветка входа, где ошибка — это не тупик: аккаунт есть, пароль верный,
     * просто письмо не открыли. Отдельная подсказка с кнопкой «выслать ещё раз» — и есть
     * весь смысл кода EMAIL_NOT_VERIFIED на фронтенде.
     */
    describe('неподтверждённый email', () => {
      const notVerified = () => serverError('EMAIL_NOT_VERIFIED', 403)

      it('предлагает выслать письмо повторно', async () => {
        const user = userEvent.setup()
        login.mockRejectedValue(notVerified())
        renderWithProviders(<LoginPage />)

        await fillAndSubmit(user, { email: 'user@example.com', password: 'secret123' })

        expect(await screen.findByText(/Email is not confirmed/)).toBeInTheDocument()
        expect(screen.getByRole('button', { name: 'Resend the email' })).toBeInTheDocument()
      })

      it('высылает письмо на тот адрес, который ввели в форму', async () => {
        const user = userEvent.setup()
        login.mockRejectedValue(notVerified())
        resendVerification.mockResolvedValue({})
        renderWithProviders(<LoginPage />)

        await fillAndSubmit(user, { email: 'user@example.com', password: 'secret123' })
        await user.click(await screen.findByRole('button', { name: 'Resend the email' }))

        // Первый аргумент, а не toHaveBeenCalledWith: react-query передаёт mutationFn
        // вторым параметром свой контекст, и он к контракту вызова отношения не имеет.
        await waitFor(() => expect(resendVerification).toHaveBeenCalled())
        expect(resendVerification.mock.calls[0][0]).toBe('user@example.com')
        expect(await screen.findByText(/Email sent again/)).toBeInTheDocument()
        // Кнопка исчезает: второй клик — это ещё одно письмо и шаг к лимиту 3 в час.
        expect(screen.queryByRole('button', { name: 'Resend the email' })).not.toBeInTheDocument()
      })

      it('обычная ошибка входа этой подсказки не показывает', async () => {
        const user = userEvent.setup()
        login.mockRejectedValue(serverError('INVALID_CREDENTIALS', 401))
        renderWithProviders(<LoginPage />)

        await fillAndSubmit(user, { email: 'user@example.com', password: 'secret123' })

        await screen.findByText('Invalid email or password')
        expect(screen.queryByRole('button', { name: 'Resend the email' })).not.toBeInTheDocument()
      })
    })
  })
})
