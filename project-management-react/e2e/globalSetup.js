import { E2E } from '../playwright.config.js'

/**
 * Фронтенд Playwright поднимет сам, а бэкенд и MailHog — нет. Без этой проверки прогон
 * падал бы через полторы минуты на таймауте «не дождались элемента», и по такому отчёту
 * невозможно понять, сломан продукт или просто не запущен docker compose. Проверяем оба
 * адреса заранее и говорим, что именно делать.
 */
async function probe(url, what, hint) {
  try {
    // Любой HTTP-ответ означает «сервис на месте»: 401 от закрытого эндпоинта — такой же
    // признак живого бэкенда, как и 200, и требовать от приложения health-эндпоинт ради
    // тестов незачем.
    await fetch(url, { signal: AbortSignal.timeout(5_000) })
  } catch (cause) {
    throw new Error(`E2E: ${what} недоступен (${url}). ${hint}`, { cause })
  }
}

export default async function globalSetup() {
  await probe(
    `${E2E.apiUrl}/notifications/unread-count`,
    'бэкенд',
    'Запустите стек: ./dev.sh (или ./mvnw initialize spring-boot:run в project-management-backend).',
  )
  await probe(
    `${E2E.mailhogUrl}/api/v2/messages?limit=1`,
    'MailHog',
    'Запустите docker compose up -d в корне репозитория — тест читает из него письмо с подтверждением.',
  )
}
