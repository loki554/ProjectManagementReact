import { defineConfig, devices } from '@playwright/test'

// Адреса живут в одном месте: сюда смотрят и сам конфиг, и globalSetup, и хелпер почты.
// Значения по умолчанию — ровно те, что поднимает ./dev.sh (см. docker-compose.yml и
// client.js), поэтому в обычной работе ничего задавать не нужно.
export const E2E = {
  frontendUrl: process.env.E2E_FRONTEND_URL ?? 'http://localhost:5173',
  apiUrl: process.env.E2E_API_URL ?? 'http://localhost:8080/api',
  mailhogUrl: process.env.E2E_MAILHOG_URL ?? 'http://localhost:8025',
}

/**
 * E2E на «золотом пути» (2.8 IMPROVEMENTS.md): регистрация → письмо → вход → проект →
 * задача → перетаскивание на канбане → списанное время. Одним сценарием и через настоящий
 * браузер — то есть тем единственным способом, которым это до сих пор проверялось руками.
 *
 * В отличие от vitest-тестов (`npm test`, jsdom и замоканная сеть) здесь ничего не
 * подменяется: браузер ходит в тот же Vite dev-сервер, тот — в настоящий бэкенд, бэкенд —
 * в настоящую Postgres и настоящий MailHog. Поэтому и запускается это отдельной командой
 * (`npm run e2e`), а не вместе с юнит-тестами: нужен поднятый стек (`./dev.sh`), и в CI это
 * будет отдельный job, а не часть быстрой проверки на каждый коммит.
 *
 * Данные тест за собой не убирает — сознательно. Он работает в общей dev-базе, каждый
 * прогон заводит нового пользователя и новый проект (уникальный суффикс во времени), и
 * ничего чужого не трогает. Чистка означала бы либо TRUNCATE в базе разработчика, либо
 * удаление через API — и то и другое хуже, чем несколько лишних строк в dev-базе.
 */
export default defineConfig({
  testDir: './e2e',
  // Сценарий один и он длинный; параллелить нечего, а общая база на несколько воркеров —
  // это ровно тот случай, когда тесты начинают мешать друг другу.
  fullyParallel: false,
  workers: 1,
  // Ретраев нет намеренно: зелёный со второй попытки — это не зелёный, а спрятанная гонка.
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : [['list']],
  globalSetup: './e2e/globalSetup.js',
  use: {
    baseURL: E2E.frontendUrl,
    // Язык интерфейса определяется по navigator (см. i18n/index.js), а тест ищет элементы
    // по видимому тексту — без фиксации локали он говорил бы на языке машины.
    locale: 'en-US',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  // Фронтенд поднимается сам, бэкенд — нет: он тянет за собой Postgres и MailHog, и решать
  // за разработчика, когда их стартовать, конфиг тестов не должен. Если стек уже запущен
  // (./dev.sh), сервер переиспользуется, а не поднимается второй раз.
  webServer: {
    command: 'npm run dev -- --port 5173 --strictPort',
    url: E2E.frontendUrl,
    reuseExistingServer: true,
    timeout: 120_000,
  },
})
