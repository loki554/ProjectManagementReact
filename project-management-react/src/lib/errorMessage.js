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
  return t(`errors.${code}`, { defaultValue: t('errors.UNKNOWN') })
}
