import { describe, expect, it, vi } from 'vitest'
import { getErrorCode, getLocalizedErrorMessage } from './errorMessage'

// Настоящий i18next здесь не нужен и мешал бы: проверяем не тексты переводов, а выбор ключа
// и то, что в него уходит. Поэтому t — заглушка, повторяющая контракт i18next: возвращает
// перевод, если он есть, иначе defaultValue, иначе сам ключ.
function fakeT(dictionary = {}) {
  return vi.fn((key, options) => {
    if (key in dictionary) {
      return typeof dictionary[key] === 'function' ? dictionary[key](options) : dictionary[key]
    }
    return options?.defaultValue ?? key
  })
}

/** Ошибка в том виде, в каком её отдаёт axios: тело ответа лежит в response.data. */
function axiosError({ code, headers } = {}) {
  return { response: { data: code ? { error: code } : {}, headers: headers ?? {} } }
}

describe('getErrorCode', () => {
  it('достаёт стабильный код из тела ответа', () => {
    expect(getErrorCode(axiosError({ code: 'PROJECT_NOT_FOUND' }))).toBe('PROJECT_NOT_FOUND')
  })

  it('возвращает null, когда ответа нет вовсе — сетевой сбой, отменённый запрос', () => {
    expect(getErrorCode({ message: 'Network Error' })).toBeNull()
    expect(getErrorCode(undefined)).toBeNull()
    expect(getErrorCode(null)).toBeNull()
  })

  it('возвращает null на ответе без поля error', () => {
    expect(getErrorCode({ response: { data: {} } })).toBeNull()
    expect(getErrorCode({ response: {} })).toBeNull()
  })
})

describe('getLocalizedErrorMessage', () => {
  it('переводит по коду из тела ответа', () => {
    const t = fakeT({ 'errors.TASK_NOT_FOUND': 'Task not found' })

    expect(getLocalizedErrorMessage(axiosError({ code: 'TASK_NOT_FOUND' }), t)).toBe('Task not found')
  })

  it('без кода отвечает общим текстом, а не пустотой', () => {
    const t = fakeT({ 'errors.UNKNOWN': 'Something went wrong' })

    expect(getLocalizedErrorMessage({ message: 'Network Error' }, t)).toBe('Something went wrong')
    expect(t).toHaveBeenCalledWith('errors.UNKNOWN')
  })

  // Бэкенд может завести новый код ошибки раньше, чем фронтенд добавит перевод. Показывать
  // пользователю голое errors.SOME_NEW_CODE нельзя — для этого коду и передаётся defaultValue.
  it('на код без перевода откатывается на общий текст, а не показывает ключ', () => {
    const t = fakeT({ 'errors.UNKNOWN': 'Something went wrong' })

    expect(getLocalizedErrorMessage(axiosError({ code: 'CODE_ADDED_ON_THE_BACKEND_YESTERDAY' }), t))
      .toBe('Something went wrong')
  })

  describe('TOO_MANY_REQUESTS', () => {
    const withRetryText = {
      'errors.TOO_MANY_REQUESTS': 'Too many attempts, try later',
      'errors.TOO_MANY_REQUESTS_RETRY': (options) => `Try again in ${options.count} min`,
    }

    it('подставляет в текст срок из Retry-After, округляя секунды вверх до минут', () => {
      const t = fakeT(withRetryText)

      // 61 секунда — это уже «через 2 минуты»: обещать минуту, которой не хватит, хуже,
      // чем попросить подождать лишние полминуты.
      expect(getLocalizedErrorMessage(
        axiosError({ code: 'TOO_MANY_REQUESTS', headers: { 'retry-after': '61' } }), t,
      )).toBe('Try again in 2 min')
    })

    it('меньше минуты — это одна минута, а не ноль', () => {
      const t = fakeT(withRetryText)

      expect(getLocalizedErrorMessage(
        axiosError({ code: 'TOO_MANY_REQUESTS', headers: { 'retry-after': '5' } }), t,
      )).toBe('Try again in 1 min')
    })

    // Заголовки axios могут приехать не обычным объектом, а AxiosHeaders — у него доступ
    // только через get(). Хелпер обязан понимать оба вида, иначе срок теряется на ровном месте.
    it('читает Retry-After и из AxiosHeaders с методом get()', () => {
      const t = fakeT(withRetryText)
      const headers = { get: (name) => (name === 'retry-after' ? '120' : null) }

      expect(getLocalizedErrorMessage(
        axiosError({ code: 'TOO_MANY_REQUESTS', headers }), t,
      )).toBe('Try again in 2 min')
    })

    // Заголовок виден кросс-доменно только потому, что бэкенд перечислил его в exposedHeaders
    // CORS. Уберут — сюда придёт undefined, и текст обязан деградировать до «попробуйте позже»,
    // а не превратиться в «попробуйте через NaN минут».
    it.each([
      ['заголовка нет', {}],
      ['заголовок пустой', { 'retry-after': '' }],
      ['заголовок не число', { 'retry-after': 'Wed, 21 Oct 2026 07:28:00 GMT' }],
      ['заголовок нулевой', { 'retry-after': '0' }],
      ['заголовок отрицательный', { 'retry-after': '-30' }],
    ])('%s — общий текст без срока', (_name, headers) => {
      const t = fakeT(withRetryText)

      expect(getLocalizedErrorMessage(
        axiosError({ code: 'TOO_MANY_REQUESTS', headers }), t,
      )).toBe('Too many attempts, try later')
    })
  })
})
