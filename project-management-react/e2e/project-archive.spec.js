import { expect, test } from '@playwright/test'
import { awaitVerificationLink } from './mailhog.js'

/**
 * Архивация проектов (4.14) через настоящий браузер.
 *
 * Смысл пункта — не два эндпоинта, а то, что видит человек: законченный проект пропадает из
 * списка, но открывается по старой ссылке, объясняет своё состояние и не показывает кнопок,
 * которые всё равно ответили бы отказом. Ровно это здесь и проверяется — вместе с обратной
 * дорогой, потому что архив без выхода был бы ловушкой. Запреты на стороне сервера покрыты
 * ProjectArchiveIntegrationTest.
 */

const RUN_ID = Date.now()

const USER = {
  email: `e2e-archive-${RUN_ID}@example.com`,
  username: `e2e-archive-${RUN_ID}`.slice(0, 30),
  password: 'e2e-password-123',
  lastName: 'Playwright',
  firstName: 'Archive',
}
const PROJECT_NAME = `E2E archive ${RUN_ID}`
const TASK = 'Ship the last thing'

test('архивный проект уходит из списка, остаётся доступным и возвращается', async ({ page }) => {
  const dialogs = []
  page.on('dialog', (dialog) => {
    dialogs.push(dialog.message())
    dialog.accept()
  })

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

  let projectUrl
  await test.step('проект с задачей', async () => {
    await page.getByRole('link', { name: 'New project' }).last().click()
    await page.getByLabel('Name').fill(PROJECT_NAME)
    await page.getByRole('button', { name: 'Create' }).click()
    await expect(page).toHaveURL(/\/projects\/e2e-archive-\d+$/)
    projectUrl = page.url()

    await page.getByRole('link', { name: 'Kanban' }).click()
    await page.getByLabel('New task').fill(TASK)
    await page.getByRole('button', { name: 'Add', exact: true }).click()
    await expect(page.getByText(TASK)).toBeVisible()
  })

  await test.step('архивация из настроек, с подтверждением', async () => {
    await page.goto(`${projectUrl}/settings/edit`)
    await page.getByRole('button', { name: 'Archive project' }).click()

    await expect.poll(() => dialogs.length).toBe(1)
    await expect(page).toHaveURL(new RegExp(`${projectUrl.split('/').pop()}$`))
    await expect(page.getByText('This project is archived')).toBeVisible()
  })

  await test.step('из списка проект ушёл, но лежит в архиве с датой', async () => {
    await page.getByRole('link', { name: 'PM Tracker' }).click()
    await expect(page).toHaveURL(/\/projects$/)
    await expect(page.getByText(PROJECT_NAME)).toHaveCount(0)

    await page.getByRole('button', { name: 'Archive' }).click()
    await expect(page.getByRole('heading', { name: 'Project archive' })).toBeVisible()
    await expect(page.getByText(PROJECT_NAME)).toBeVisible()
    await expect(page.getByText(/Archived on /)).toBeVisible()
  })

  await test.step('архивный проект открывается и читается, но не правится', async () => {
    await page.getByText(PROJECT_NAME).click()
    await expect(page.getByText('This project is archived')).toBeVisible()
    // Настройки архивного проекта не правятся — значит и кнопки к ним нет.
    await expect(page.getByRole('link', { name: 'Edit project' })).toHaveCount(0)

    await page.getByRole('link', { name: 'Kanban' }).click()
    await expect(page.getByText(TASK)).toBeVisible()
    // Быстрое добавление задачи исчезло: сервер такой запрос всё равно отклонит.
    await expect(page.getByLabel('New task')).toHaveCount(0)
  })

  await test.step('возврат из архива возвращает и правки', async () => {
    await page.goto(projectUrl)
    await page.getByRole('button', { name: 'Restore from archive' }).click()
    await expect(page.getByText('This project is archived')).toHaveCount(0)

    await page.getByRole('link', { name: 'Kanban' }).click()
    await expect(page.getByLabel('New task')).toBeVisible()

    await page.getByRole('link', { name: 'PM Tracker' }).click()
    await expect(page.getByText(PROJECT_NAME)).toBeVisible()
  })

  // Спрашивают ровно один раз — при архивации. Возврат из архива не спрашивает: он ничего
  // не ломает и отменяется тем же нажатием.
  expect(dialogs).toHaveLength(1)
})
