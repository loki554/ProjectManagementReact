import { expect, test } from '@playwright/test'
import { awaitVerificationLink } from './mailhog.js'

/**
 * Фильтры в адресе и сохранённые представления (4.7, 5.5) через настоящий браузер.
 *
 * Здесь проверяется ровно то, чего не видит ничто другое. Сборку адреса и тела запроса
 * покрывает `lib/taskFilters.test.js`, права и персональность представлений —
 * `SavedViewIntegrationTest`. А вот что фильтр действительно доезжает до адресной строки,
 * что перезагрузка возвращает тот же отфильтрованный список (ради чего пункт и заведён:
 * ссылку отправляют коллеге), что возврат из открытой задачи не теряет фильтр и что
 * сохранённое представление применяется одной кнопкой — это навигация и история браузера,
 * то есть то, чего в jsdom нет.
 *
 * Задачи заводятся через UI доски (там это одна строка ввода), а проверяются на странице
 * списка: фильтры и представления живут только там.
 */

const RUN_ID = Date.now()

const USER = {
  email: `e2e-views-${RUN_ID}@example.com`,
  username: `e2e-views-${RUN_ID}`.slice(0, 30),
  password: 'e2e-password-123',
  lastName: 'Playwright',
  firstName: 'Views',
}
const PROJECT_NAME = `E2E saved views ${RUN_ID}`
const VIEW_NAME = 'Nobody home'
// Название третьей задачи не содержит слова report — на нём и проверяется поиск.
const TASKS = ['Alpha report', 'Beta report', 'Gamma memo']

function row(page, title) {
  return page.getByRole('row').filter({ hasText: title })
}

test('фильтры живут в адресе, а сохранённое представление применяется одной кнопкой', async ({ page }) => {
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

  await test.step('проект и три задачи', async () => {
    await page.getByRole('link', { name: 'New project' }).last().click()
    await page.getByLabel('Name').fill(PROJECT_NAME)
    await page.getByRole('button', { name: 'Create' }).click()
    await expect(page).toHaveURL(/\/projects\/e2e-saved-views-\d+$/)

    await page.getByRole('link', { name: 'Kanban' }).click()
    for (const title of TASKS) {
      await page.getByLabel('New task').fill(title)
      await page.getByRole('button', { name: 'Add', exact: true }).click()
      await expect(page.getByText(title)).toBeVisible()
    }

    await page.getByRole('link', { name: 'Task list' }).click()
    await expect(page).toHaveURL(/\/tasks$/)
    await expect(page.getByText('Tasks: 3')).toBeVisible()
  })

  await test.step('поиск уезжает в адрес и переживает перезагрузку', async () => {
    await page.getByPlaceholder('Search by title...').fill('memo')

    await expect(page).toHaveURL(/\?q=memo$/)
    await expect(page.getByText('Tasks: 1')).toBeVisible()

    // Главное здесь: перезагрузка. Пока фильтры жили в useState, она возвращала полный
    // список, и отправить кому-то отфильтрованную ссылку было невозможно.
    await page.reload()
    await expect(page.getByPlaceholder('Search by title...')).toHaveValue('memo')
    await expect(page.getByText('Tasks: 1')).toBeVisible()
    await expect(row(page, 'Gamma memo')).toBeVisible()
  })

  await test.step('готовое представление применяется кнопкой и переписывает адрес', async () => {
    await page.getByRole('button', { name: 'Unassigned', exact: true }).click()

    await expect(page).toHaveURL(/\?assignee=__unassigned__$/)
    // Поиск снят вместе с применением представления — это набор фильтров целиком, а не
    // добавка к тому, что уже стояло.
    await expect(page.getByPlaceholder('Search by title...')).toHaveValue('')
    await expect(page.getByText('Tasks: 3')).toBeVisible()
  })

  await test.step('текущие фильтры сохраняются под именем', async () => {
    await page.getByRole('button', { name: 'Save view' }).click()
    await page.getByLabel('View name').fill(VIEW_NAME)
    await page.getByRole('button', { name: 'Save', exact: true }).click()

    await expect(page.getByText(`View “${VIEW_NAME}” saved`)).toBeVisible()
    await expect(page.getByRole('button', { name: VIEW_NAME, exact: true })).toBeVisible()
  })

  await test.step('представление переживает перезагрузку и остаётся подсвеченным', async () => {
    await page.reload()

    // Активность считается сравнением фильтров с адресом, а не хранится в нём: у чипа
    // должно быть aria-pressed, хотя после перезагрузки страница ничего не «нажимала».
    await expect(page.getByRole('button', { name: VIEW_NAME, exact: true })).toHaveAttribute('aria-pressed', 'true')
  })

  await test.step('сброс и повторное применение представления', async () => {
    await page.getByRole('button', { name: 'All tasks' }).click()
    await expect(page).toHaveURL(/\/tasks$/)
    await expect(page.getByRole('button', { name: VIEW_NAME, exact: true })).toHaveAttribute('aria-pressed', 'false')

    await page.getByRole('button', { name: VIEW_NAME, exact: true }).click()
    await expect(page).toHaveURL(/\?assignee=__unassigned__$/)
    await expect(page.getByRole('button', { name: VIEW_NAME, exact: true })).toHaveAttribute('aria-pressed', 'true')
  })

  await test.step('возврат из открытой задачи не теряет фильтр', async () => {
    await row(page, 'Alpha report').click()
    await expect(page).toHaveURL(/\/tasks\/\d+$/)

    await page.goBack()
    await expect(page).toHaveURL(/\?assignee=__unassigned__$/)
    await expect(page.getByRole('button', { name: VIEW_NAME, exact: true })).toHaveAttribute('aria-pressed', 'true')
  })

  await test.step('удаление убирает представление из панели', async () => {
    await page.getByRole('button', { name: `Delete view “${VIEW_NAME}”` }).click()

    await expect(page.getByText(`View “${VIEW_NAME}” deleted`)).toBeVisible()
    await expect(page.getByRole('button', { name: VIEW_NAME, exact: true })).toHaveCount(0)
    // Фильтр при этом остался: удалили закладку, а не то, на что она смотрела.
    await expect(page).toHaveURL(/\?assignee=__unassigned__$/)
  })
})
