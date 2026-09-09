import { expect, test } from '@playwright/test'
import { awaitVerificationLink } from './mailhog.js'

/**
 * Error Boundary (5.1) через настоящий браузер.
 *
 * Проверяется то, чего не видно ни в vitest, ни в интеграционных тестах бэкенда: что
 * секционная граница вокруг канбана действительно стоит на пути настоящей ошибки рендера и
 * что страница вокруг неё остаётся живой — хедер, сайдбар, форма создания задачи. Ошибку
 * приходится подстраивать самим: приложение специально не падает, поэтому ответ сервера
 * подменяется в браузере на такой, от которого React обязан бросить исключение при рендере
 * карточки (объект вместо строки в заголовке).
 *
 * Это же и проверка того, что граница не «залипает»: убрали подмену, перечитали доску — и
 * доска на месте, без перезагрузки приложения и без следов на других экранах.
 */

const RUN_ID = Date.now()

const USER = {
  email: `e2e-boundary-${RUN_ID}@example.com`,
  username: `e2e-boundary-${RUN_ID}`.slice(0, 30),
  password: 'e2e-password-123',
  lastName: 'Playwright',
  firstName: 'Boundary',
}
const PROJECT_NAME = `E2E boundary ${RUN_ID}`
const TASK_TITLE = 'Task that breaks the board'

test('ошибка рендера гасит ровно один уровень: доску — секционная граница, страницу — маршрутная', async ({ page }) => {
  await test.step('регистрация, подтверждение и вход', async () => {
    await page.goto('/register')
    await page.getByLabel('Email').fill(USER.email)
    await page.getByLabel('Username').fill(USER.username)
    await page.getByLabel('Password').fill(USER.password)
    await page.getByLabel('Last name').fill(USER.lastName)
    await page.getByLabel('First name').fill(USER.firstName)
    await page.getByRole('button', { name: 'Sign up' }).click()
    await expect(page.getByText('Check your email')).toBeVisible()

    await page.goto(await awaitVerificationLink(USER.email))
    await page.getByRole('link', { name: 'Go to sign in' }).click()

    await page.getByLabel('Email').fill(USER.email)
    await page.getByLabel('Password').fill(USER.password)
    await page.getByRole('button', { name: 'Sign in' }).click()
    await expect(page).toHaveURL(/\/projects$/)
  })

  await test.step('проект и задача на доске', async () => {
    await page.getByRole('link', { name: 'New project' }).last().click()
    await page.getByLabel('Name').fill(PROJECT_NAME)
    await page.getByRole('button', { name: 'Create' }).click()
    await expect(page).toHaveURL(/\/projects\/e2e-boundary-\d+$/)

    await page.getByRole('link', { name: 'Kanban', exact: true }).click()
    await page.getByLabel('New task').fill(TASK_TITLE)
    await page.getByRole('button', { name: 'Add', exact: true }).click()
    await expect(page.getByText(TASK_TITLE)).toBeVisible()
  })

  await test.step('сломанный ответ роняет доску — и только доску', async () => {
    // Объект вместо строки в заголовке: React бросает "Objects are not valid as a React
    // child" ровно там, где карточка рисует заголовок, то есть внутри границы.
    await page.route('**/tasks/board', async (route) => {
      const response = await route.fetch()
      const tasks = await response.json()
      route.fulfill({
        response,
        json: tasks.map((task) => ({ ...task, title: { broken: true } })),
      })
    })
    await page.reload()

    await expect(page.getByText('The board failed to render')).toBeVisible()
    await expect(page.getByText('Open the task list instead.')).toBeVisible()
    // Страница вокруг жива: хедер, сайдбар проекта и форма быстрого создания задачи — всё
    // на месте, а значит упала именно доска, а не вкладка.
    await expect(page.getByRole('link', { name: 'Kanban', exact: true })).toBeVisible()
    await expect(page.getByLabel('New task')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Add', exact: true })).toBeEnabled()
    // И это не общий экран «что-то пошло не так» — маршрутная граница не сработала.
    await expect(page.getByText('Something went wrong')).toHaveCount(0)
  })

  await test.step('доска возвращается сама, как только данные снова целы', async () => {
    await page.unroute('**/tasks/board')
    // Не перезагружаем страницу: смысл resetKeys в том, что свежие данные снимают ошибку
    // сами. Уход на список задач и обратно заставляет react-query перечитать доску.
    await page.getByRole('link', { name: 'Task list', exact: true }).click()
    await page.getByRole('link', { name: 'Kanban', exact: true }).click()

    await expect(page.getByText(TASK_TITLE)).toBeVisible()
    await expect(page.getByText('The board failed to render')).toHaveCount(0)
  })

  await test.step('падение вне секций ловит маршрутная граница', async () => {
    // Тот же приём, но на списке задач: вокруг него своей границы нет, и ловить
    // ошибку должна следующая по дереву — маршрутная.
    await page.route(
      (url) => url.pathname.endsWith('/tasks'),
      async (route) => {
        const response = await route.fetch()
        const listPage = await response.json()
        route.fulfill({
          response,
          json: { ...listPage, items: listPage.items.map((task) => ({ ...task, title: { broken: true } })) },
        })
      },
    )
    await page.getByRole('link', { name: 'Task list', exact: true }).click()

    await expect(page.getByRole('heading', { name: 'Something went wrong' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Try again' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Reload page' })).toBeVisible()
    // Сайдбар проекта ушёл вместе со страницей — это и есть разница между уровнями границ.
    await expect(page.getByRole('link', { name: 'Kanban', exact: true })).toHaveCount(0)

    // А вот роутер жив: ссылка с экрана уводит к проектам, а не в тупик.
    await page.unroute((url) => url.pathname.endsWith('/tasks'))
    await page.getByRole('link', { name: 'Go to projects' }).click()
    await expect(page).toHaveURL(/\/projects$/)
    await expect(page.getByText(PROJECT_NAME)).toBeVisible()
  })
})
