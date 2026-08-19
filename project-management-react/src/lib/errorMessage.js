// Бэкенд всегда отвечает на ошибки телом { error, message } (см. GlobalExceptionHandler
// на бэке), но message там захардкожен на русском — поэтому для показа пользователю
// используем стабильный код error и мапим его на перевод через i18n (см. errors.* в
// src/i18n/locales/*.json), а не показываем backend message напрямую.
export function getErrorCode(error) {
  return error?.response?.data?.error ?? null
}

// t — функция перевода из useTranslation(). Код ошибки, для которого нет перевода
// (например, сетевой сбой без ответа от сервера), падает на errors.UNKNOWN.
export function getLocalizedErrorMessage(error, t) {
  const code = getErrorCode(error)
  if (!code) {
    return t('errors.UNKNOWN')
  }
  // 429 от rate limiter'а бэкенда (см. AuthRateLimitFilter) приходит с Retry-After —
  // «попробуйте позже» без срока бесполезно, поэтому подставляем его в текст.
  if (code === 'TOO_MANY_REQUESTS') {
    const minutes = getRetryAfterMinutes(error)
    if (minutes !== null) {
      return t('errors.TOO_MANY_REQUESTS_RETRY', { count: minutes })
    }
  }
  return t(`errors.${code}`, { defaultValue: t('errors.UNKNOWN') })
}

// Retry-After бэкенд отдаёт в секундах; округляем вверх до минут — на этом горизонте
// («попробуйте через N минут») точность до секунды всё равно не нужна.
// Заголовок виден кросс-доменно только потому, что он перечислен в exposedHeaders
// CORS-конфигурации на бэке; если его там не будет, вернётся undefined и текст
// откатится на общий errors.TOO_MANY_REQUESTS.
function getRetryAfterMinutes(error) {
  const headers = error?.response?.headers
  const raw = headers?.['retry-after'] ?? headers?.get?.('retry-after')
  const seconds = Number(raw)
  if (!Number.isFinite(seconds) || seconds <= 0) {
    return null
  }
  return Math.max(1, Math.ceil(seconds / 60))
}
