import { expect, test } from '@playwright/test'
import { awaitVerificationLink } from './mailhog.js'

/**
 * Отчёт по времени (4.10) и дашборд проекта (4.11) через настоящий браузер.
 *
 * Здесь проверяется то, чего не видит ничто другое. Арифметику полос и разбор
 * Content-Disposition покрывает `lib/reports.test.js`, агрегаты, права, границы периода и
 * восстановление burndown по истории статусов — `ReportIntegrationTest`. А вот что обе
 * страницы открываются из сайдбара; что отчёт показывает часы того, кто их только что
 * списал; что кнопка «Download CSV» действительно отдаёт браузеру файл с правильным именем;
 * и что дашборд рисует линию спринта, а не пустую карточку, — это навигация, скачивание и
 * SVG, то есть ровно то, чего в jsdom нет.
 */

const RUN_ID = Date.now()

const USER = {
  email: `e2e-reports-${RUN_ID}@example.com`,
  username: `e2e-reports-${RUN_ID}`.slice(0, 30),
  password: 'e2e-password-123',
  lastName: 'Playwright',
  firstName: 'Reports',
}
const PROJECT_NAME = `E2E reporting ${RUN_ID}`
const TASKS = ['Ship the API', 'Write the docs']
const HOURS = ['3.5', '1.25']

test('часы попадают в отчёт и выгружаются в CSV, а спринт — в burndown дашборда', async ({ page }) => {
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

  await test.step('проект и две задачи', async () => {
    await page.getByRole('link', { name: 'New project' }).last().click()
    await page.getByLabel('Name').fill(PROJECT_NAME)
    await page.getByRole('button', { name: 'Create' }).click()
    await expect(page).toHaveURL(/\/projects\/e2e-reporting-\d+$/)

    await page.getByRole('link', { name: 'Kanban' }).click()
    for (const title of TASKS) {
      await page.getByLabel('New task').fill(title)
      await page.getByRole('button', { name: 'Add', exact: true }).click()
      await expect(page.getByText(title)).toBeVisible()
    }
  })

  await test.step('часы списываются на обе задачи', async () => {
    for (const [index, title] of TASKS.entries()) {
      await page.getByRole('link', { name: 'Kanban' }).click()
      await page.getByText(title).click()
      await expect(page).toHaveURL(/\/tasks\/\d+$/)

      // Дата уже подставлена сегодняшним числом — трогаем только часы.
      await page.getByLabel('Hours', { exact: true }).fill(HOURS[index])
      await page.getByRole('button', { name: 'Log time' }).click()
      await expect(page.getByText(`Total: ${Number(HOURS[index]).toFixed(2)}h`)).toBeVisible()
    }
  })

  // Кнопка в сайдбаре — часть пункта: отчёт это рабочий экран, а не раздел настроек.
  await test.step('отчёт открывается из сайдбара и складывает часы', async () => {
    await page.getByRole('link', { name: 'Time', exact: true }).click()
    await expect(page).toHaveURL(/\/reports\/time$/)

    // 3.5 + 1.25 — итог, фамилия автора в разрезе по участникам, обе задачи в разрезе
    // по задачам. Период по умолчанию — текущий месяц, и «сегодня» в него входит.
    await expect(page.getByText('4.75 h').first()).toBeVisible()
    // last(): то же имя стоит выше в <option> фильтра по участнику — это разметка формы,
    // а увидеть надо строку разреза. Рядом с ней число записей: две, по одной на задачу.
    await expect(page.getByText(`${USER.lastName} ${USER.firstName}`).last()).toBeVisible()
    await expect(page.getByText('2 entries')).toBeVisible()
    for (const title of TASKS) {
      await expect(page.getByRole('link', { name: title })).toBeVisible()
    }
  })

  await test.step('период за пределами сегодняшнего дня оставляет отчёт пустым', async () => {
    // exact: без него «To» ловит ещё и aria-label переключателя темы («Switch to dark theme»).
    await page.getByLabel('From', { exact: true }).fill('2020-01-01')
    await page.getByLabel('To', { exact: true }).fill('2020-01-31')
    await page.getByRole('button', { name: 'Show' }).click()

    await expect(page.getByText('0 h').first()).toBeVisible()
    await expect(page.getByText('No time logged in this period').first()).toBeVisible()

    // И обратно — пресетом, а не руками: он должен и заполнять поля, и сразу применять.
    await page.getByRole('button', { name: 'This month' }).click()
    await expect(page.getByText('4.75 h').first()).toBeVisible()
  })

  /**
   * Скачивание — единственная часть пункта, которую нельзя проверить ничем, кроме
   * браузера: файл требует авторизации, поэтому он едет blob'ом через apiClient, а имя
   * ему выбирает фронтенд по Content-Disposition — заголовку, который до JS доезжает
   * только потому, что бэкенд явно выставил его в exposedHeaders (см. SecurityConfig).
   */
  await test.step('CSV скачивается с именем, которое собрал сервер', async () => {
    const downloadPromise = page.waitForEvent('download')
    await page.getByRole('button', { name: 'Download CSV' }).click()
    const download = await downloadPromise

    expect(download.suggestedFilename()).toMatch(/^time-report-e2e-reporting-\d+-\d{4}-\d{2}-\d{2}_\d{4}-\d{2}-\d{2}\.csv$/)
  })

  await test.step('дашборд открывается из сайдбара и считает задачи', async () => {
    await page.getByRole('link', { name: 'Dashboard' }).click()
    await expect(page).toHaveURL(/\/dashboard$/)

    await expect(page.getByText('Tasks total')).toBeVisible()
    // Обе задачи заведены и обе открыты; закрытых нет.
    await expect(page.getByText('2', { exact: true }).first()).toBeVisible()
    // Спринтов в проекте нет — и дашборд говорит об этом прямо, а не рисует пустой график.
    await expect(page.getByText('No sprints yet — nothing to chart')).toBeVisible()
  })

  await test.step('спринт с задачами появляется на графике выгорания', async () => {
    await page.getByRole('link', { name: 'Sprints', exact: true }).click()
    await page.getByRole('button', { name: '+ New sprint' }).click()
    await page.getByLabel('Name').fill('Reporting sprint')
    const today = new Date().toISOString().slice(0, 10)
    await page.getByLabel('Start').fill(today)
    await page.getByLabel('End').fill(today)
    await page.getByRole('button', { name: 'New sprint' }).last().click()
    await expect(page.getByText('Reporting sprint').first()).toBeVisible()

    for (const title of TASKS) {
      await page.getByRole('listitem').filter({ hasText: title }).getByRole('button', { name: 'To sprint' }).click()
    }

    await page.getByRole('link', { name: 'Dashboard' }).click()
    await expect(page.getByRole('link', { name: 'Reporting sprint' })).toBeVisible()
    await expect(page.getByText('2 tasks')).toBeVisible()
    // Сам график — SVG с подписью; проверяем, что он вообще нарисован, а не заменён
    // сообщением «нечего показывать».
    await expect(page.getByRole('img', { name: /Burndown chart for sprint/ })).toBeVisible()
  })
})
