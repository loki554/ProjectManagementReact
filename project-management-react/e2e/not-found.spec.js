import { expect, test } from '@playwright/test'
import { awaitVerificationLink } from './mailhog.js'

/**
 * Состояния «ничего нет по этому адресу» (5.3) в настоящем браузере.
 *
 * Раньше любой неверный URL молча уезжал на список проектов, а несуществующий проект или
 * задача показывали красную строчку внутри страницы, которая всё равно пыталась рисоваться.
 * Проверяется, что теперь у каждого случая свой ответ и из каждого есть выход — и что
 * ошибка адреса не выдаётся за пропавшие данные.
 */

const RUN_ID = Date.now()

const USER = {
  email: `e2e-404-${RUN_ID}@example.com`,
  username: `e2e-404-${RUN_ID}`.slice(0, 30),
  password: 'e2e-password-123',
  lastName: 'Playwright',
  firstName: 'Missing',
}
const PROJECT_NAME = `E2E 404 ${RUN_ID}`

test('битый адрес, чужой проект и несуществующая задача выглядят по-разному', async ({ page }) => {
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

  await test.step('несуществующий адрес — 404, а не тихий редирект', async () => {
    await page.goto('/nowhere/at/all')

    await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible()
    // Адрес остался в строке браузера: подменять его редиректом значило бы прятать саму
    // опечатку — а искать её человеку.
    await expect(page).toHaveURL(/\/nowhere\/at\/all$/)
    await expect(page.getByText('/nowhere/at/all')).toBeVisible()

    await page.getByRole('link', { name: 'Go to projects' }).click()
    await expect(page).toHaveURL(/\/projects$/)
  })

  let projectSlug

  await test.step('проект и задача', async () => {
    await page.getByRole('link', { name: 'New project' }).last().click()
    await page.getByLabel('Name').fill(PROJECT_NAME)
    await page.getByRole('button', { name: 'Create' }).click()
    await expect(page).toHaveURL(/\/projects\/e2e-404-\d+$/)
    projectSlug = page.url().split('/').pop()
  })

  await test.step('несуществующий проект: свой экран и никакого сайдбара', async () => {
    await page.goto('/projects/there-is-no-such-project')

    await expect(page.getByRole('heading', { name: 'Project not found' })).toBeVisible()
    // Сайдбара нет: разделы проекта, которого нет, — навигация в никуда.
    await expect(page.getByRole('link', { name: 'Kanban', exact: true })).toHaveCount(0)
    await expect(page.getByRole('link', { name: 'Go to projects' })).toBeVisible()
  })

  await test.step('несуществующая задача: про номер, а не про пропажу', async () => {
    await page.goto(`/projects/${projectSlug}/tasks/9999`)

    await expect(page.getByRole('heading', { name: 'Task not found' })).toBeVisible()
    // Обе настоящие причины названы, и до корзины отсюда один клик.
    await expect(page.getByText(/Check the number in the address/)).toBeVisible()
    await page.getByRole('link', { name: 'Open the trash' }).click()
    await expect(page).toHaveURL(new RegExp(`/projects/${projectSlug}/trash$`))
  })

  await test.step('несуществующий раздел проекта тоже доходит до 404', async () => {
    await page.goto(`/projects/${projectSlug}/nonsense`)

    await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible()
  })
})
