import axios from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient, refreshSession } from './client'
import { useAuthStore } from '../stores/authStore'
import { useToastStore } from '../stores/toastStore'

/**
 * Интерсептор обновления сессии — то место, где «работает у меня в браузере» ничего не
 * доказывает: интересное начинается на нескольких одновременных запросах, а руками такое
 * воспроизводится через раз.
 *
 * Ставка здесь одна и высокая. С версии 1.6 бэкенд считает повторное предъявление уже
 * ротированного refresh-токена кражей и гасит ВСЕ сессии пользователя. Значит, два
 * параллельных обновления — это не «лишний запрос», а разлогин на всех устройствах
 * посреди работы. Тесты ниже проверяют ровно это: сколько раз ушёл refresh и с каким
 * токеном, а не то, что запрос как-то отработал.
 *
 * Сеть подменена на уровне адаптера axios, без mock-сервера: адаптер — штатная точка
 * расширения, он даёт доступ к самому config (заголовок Authorization, тело запроса) и не
 * тащит в проект ещё одну зависимость.
 */

const REFRESH_URL = '/auth/refresh'

/** Что должна ответить «сеть» на очередной запрос. Задаётся каждым тестом. */
let respond

/** Все запросы, ушедшие через адаптер, — по ним и считаем обращения к refresh. */
let sentRequests

function testAdapter(config) {
  sentRequests.push({
    url: config.url,
    authorization: authorizationOf(config),
    body: config.data ? JSON.parse(config.data) : null,
  })

  const result = respond(config)
  const response = {
    data: result.data ?? {},
    status: result.status,
    statusText: '',
    headers: result.headers ?? {},
    config,
    request: {},
  }

  return result.status >= 200 && result.status < 300
    ? Promise.resolve(response)
    : Promise.reject(new axios.AxiosError('Request failed', 'ERR_BAD_RESPONSE', config, {}, response))
}

// В адаптере заголовки — это AxiosHeaders, а не обычный объект.
function authorizationOf(config) {
  return config.headers?.get?.('Authorization') ?? config.headers?.Authorization ?? null
}

function isRefresh(config) {
  return config.url?.includes(REFRESH_URL)
}

function refreshCount() {
  return sentRequests.filter((request) => request.url.includes(REFRESH_URL)).length
}

function session({ accessToken = null, refreshToken = null } = {}) {
  useAuthStore.setState({ accessToken, refreshToken, user: accessToken ? { id: 'u1' } : null })
}

const NEW_SESSION = { accessToken: 'access-2', refreshToken: 'refresh-2', user: { id: 'u1' } }

beforeEach(() => {
  axios.defaults.adapter = testAdapter
  apiClient.defaults.adapter = testAdapter
  sentRequests = []
  respond = () => ({ status: 200 })
  session()
  useToastStore.setState({ toasts: [] })
  localStorage.clear()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('параллельные 401', () => {
  /**
   * Тот самый сценарий из-за которого в client.js вообще появился refreshPromise: страница
   * при открытии выстреливает несколько запросов сразу, access-токен у всех протух, и без
   * схлопывания каждый пошёл бы обновлять сессию самостоятельно.
   */
  it('три запроса с протухшим токеном обновляют сессию ровно один раз', async () => {
    session({ accessToken: 'access-1', refreshToken: 'refresh-1' })
    respond = (config) => {
      if (isRefresh(config)) return { status: 200, data: NEW_SESSION }
      return authorizationOf(config) === 'Bearer access-2'
        ? { status: 200, data: { ok: true } }
        : { status: 401, data: { error: 'UNAUTHENTICATED' } }
    }

    const responses = await Promise.all([
      apiClient.get('/projects'),
      apiClient.get('/notifications'),
      apiClient.get('/tasks/mine'),
    ])

    expect(refreshCount()).toBe(1)
    expect(responses.map((response) => response.data)).toEqual([{ ok: true }, { ok: true }, { ok: true }])
    expect(useAuthStore.getState().accessToken).toBe('access-2')
  })

  it('каждый из них доигрывается уже с новым токеном, а не теряется', async () => {
    session({ accessToken: 'access-1', refreshToken: 'refresh-1' })
    respond = (config) => {
      if (isRefresh(config)) return { status: 200, data: NEW_SESSION }
      return authorizationOf(config) === 'Bearer access-2'
        ? { status: 200, data: { ok: true } }
        : { status: 401, data: { error: 'UNAUTHENTICATED' } }
    }

    await Promise.all([apiClient.get('/projects'), apiClient.get('/notifications')])

    const retried = sentRequests.filter((request) => request.authorization === 'Bearer access-2')
    expect(retried.map((request) => request.url).sort()).toEqual(['/notifications', '/projects'])
  })

  it('если обновиться не удалось — сессия гаснет, а тост показывается один раз на всех', async () => {
    session({ accessToken: 'access-1', refreshToken: 'refresh-1' })
    respond = (config) =>
      isRefresh(config)
        ? { status: 401, data: { error: 'INVALID_REFRESH_TOKEN' } }
        : { status: 401, data: { error: 'UNAUTHENTICATED' } }

    const results = await Promise.allSettled([apiClient.get('/projects'), apiClient.get('/notifications')])

    expect(results.map((result) => result.status)).toEqual(['rejected', 'rejected'])
    expect(refreshCount()).toBe(1)
    expect(useAuthStore.getState().accessToken).toBeNull()
    expect(useAuthStore.getState().refreshToken).toBeNull()
    // Три одинаковых «сессия истекла» подряд — это не информирование, а шум.
    expect(useToastStore.getState().toasts).toHaveLength(1)
    expect(useToastStore.getState().toasts[0]).toMatchObject({
      message: 'Your session has expired, please sign in again',
      variant: 'error',
    })
  })
})

describe('когда обновлять не нужно', () => {
  it('401 без refresh-токена гасит сессию, не пытаясь обновиться', async () => {
    session({ accessToken: 'access-1', refreshToken: null })
    respond = () => ({ status: 401, data: { error: 'UNAUTHENTICATED' } })

    await expect(apiClient.get('/projects')).rejects.toThrow()

    expect(refreshCount()).toBe(0)
    expect(useAuthStore.getState().accessToken).toBeNull()
    expect(useToastStore.getState().toasts).toHaveLength(1)
  })

  // На /auth/login 401 означает «неверный пароль», а не «сессия протухла». Попытка
  // обновиться здесь и бессмысленна, и вредна: пользователь увидел бы «сессия истекла»
  // вместо сообщения об ошибке входа.
  it.each([
    ['/auth/login'],
    ['/auth/register'],
    ['/auth/refresh'],
    ['/auth/verify-email'],
    ['/auth/resend-verification'],
  ])('401 на %s не запускает обновление и не трогает сессию', async (url) => {
    session({ accessToken: 'access-1', refreshToken: 'refresh-1' })
    respond = () => ({ status: 401, data: { error: 'INVALID_CREDENTIALS' } })

    await expect(apiClient.post(url, {})).rejects.toThrow()

    // Считаем все запросы, а не только refresh: сам /auth/refresh тоже в этом списке,
    // и для него вопрос ровно тот же — что за ним не пошёл второй, рекурсивный.
    expect(sentRequests).toHaveLength(1)
    expect(useAuthStore.getState().accessToken).toBe('access-1')
    expect(useToastStore.getState().toasts).toHaveLength(0)
  })

  it('не-401 пробрасывается как есть', async () => {
    session({ accessToken: 'access-1', refreshToken: 'refresh-1' })
    respond = () => ({ status: 500, data: { error: 'INTERNAL_ERROR' } })

    await expect(apiClient.get('/projects')).rejects.toMatchObject({
      response: { status: 500, data: { error: 'INTERNAL_ERROR' } },
    })

    expect(refreshCount()).toBe(0)
    expect(useAuthStore.getState().accessToken).toBe('access-1')
  })

  /**
   * Защита от бесконечного круга: если запрос вернул 401 и после успешного обновления,
   * второй раз обновляться нельзя — иначе получится цикл «401 → refresh → 401 → refresh»,
   * который на бэкенде выглядит как перебор и упирается в rate limiter.
   */
  it('повторный 401 уже обновлённого запроса не уходит на второй круг', async () => {
    session({ accessToken: 'access-1', refreshToken: 'refresh-1' })
    respond = (config) =>
      isRefresh(config)
        ? { status: 200, data: NEW_SESSION }
        : { status: 401, data: { error: 'UNAUTHENTICATED' } }

    await expect(apiClient.get('/projects')).rejects.toThrow()

    expect(refreshCount()).toBe(1)
  })
})

describe('обмен токена', () => {
  /**
   * Соседняя вкладка успела обновить сессию, пока мы стояли за замком: в localStorage уже
   * новый токен, а в памяти этой вкладки — старый, уже ротированный. Предъявить его — то же
   * самое, что предъявить украденный: бэкенд погасит все сессии пользователя.
   */
  it('берёт refresh-токен из localStorage, а не из памяти вкладки', async () => {
    session({ accessToken: 'access-1', refreshToken: 'stale-in-this-tab' })
    localStorage.setItem(
      'pmtracker-auth',
      JSON.stringify({ state: { refreshToken: 'rotated-by-another-tab', user: { id: 'u1' } }, version: 0 }),
    )
    respond = () => ({ status: 200, data: NEW_SESSION })

    await refreshSession()

    expect(sentRequests.at(-1).body).toEqual({ refreshToken: 'rotated-by-another-tab' })
  })

  it('без токена в хранилище не ходит в сеть вовсе', async () => {
    session({ accessToken: 'access-1', refreshToken: null })

    await expect(refreshSession()).rejects.toThrow(/no longer available/)
    expect(refreshCount()).toBe(0)
  })

  it('складывает новую пару в стор', async () => {
    session({ accessToken: 'access-1', refreshToken: 'refresh-1' })
    respond = () => ({ status: 200, data: NEW_SESSION })

    await refreshSession()

    expect(useAuthStore.getState()).toMatchObject({
      accessToken: 'access-2',
      refreshToken: 'refresh-2',
    })
  })

  // Второй уровень эксклюзивности: refreshPromise схлопывает параллельные вызовы внутри
  // вкладки, Web Locks — между вкладками. Проверяем, что замок действительно берётся, и
  // именно тот (имя общее для всех вкладок, иначе смысла в нём нет).
  it('берёт межвкладочный замок, когда браузер их поддерживает', async () => {
    const request = vi.fn((name, callback) => callback())
    Object.defineProperty(navigator, 'locks', { value: { request }, configurable: true })
    session({ accessToken: 'access-1', refreshToken: 'refresh-1' })
    respond = () => ({ status: 200, data: NEW_SESSION })

    try {
      await refreshSession()
    } finally {
      delete navigator.locks
    }

    expect(request).toHaveBeenCalledTimes(1)
    expect(request.mock.calls[0][0]).toBe('pmtracker-auth-refresh')
  })

  // navigator.locks нет в небезопасном контексте (http на IP в локальной сети) и в старых
  // браузерах — обновление обязано продолжать работать, пусть и с защитой только внутри вкладки.
  it('без Web Locks обновляется по-прежнему', async () => {
    expect(navigator.locks).toBeUndefined()
    session({ accessToken: 'access-1', refreshToken: 'refresh-1' })
    respond = () => ({ status: 200, data: NEW_SESSION })

    await refreshSession()

    expect(refreshCount()).toBe(1)
    expect(useAuthStore.getState().accessToken).toBe('access-2')
  })
})
