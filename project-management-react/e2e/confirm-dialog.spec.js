import { expect, test } from '@playwright/test'
import { awaitVerificationLink } from './mailhog.js'

/**
 * Диалог подтверждения (5.2) через настоящий браузер.
 *
 * Проверяется то, чего нет ни в vitest, ни на бэкенде: Esc и фокус-трап нативного
 * `<dialog>` — jsdom модальность не реализует вовсе, поэтому там они не проверяются в
 * принципе (см. test/setup.js). И заодно главное обещание пункта: отказ действительно
 * ничего не делает, а согласие — делает; для задачи в вопросе перечислено, что именно
 * уедет в корзину вместе с ней.
 */

const RUN_ID = Date.now()

const USER = {
  email: `e2e-confirm-${RUN_ID}@example.com`,
  username: `e2e-confirm-${RUN_ID}`.slice(0, 30),
  password: 'e2e-password-123',
  lastName: 'Playwright',
  firstName: 'Confirm',
}
const PROJECT_NAME = `E2E confirm ${RUN_ID}`
const TAG_NAME = 'doomed-tag'
const TASK_TITLE = 'Task with a history'

test('деструктивные действия спрашивают, и отказ действительно отказ', async ({ page }) => {
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

  await test.step('проект и тэг', async () => {
    await page.getByRole('link', { name: 'New project' }).last().click()
    await page.getByLabel('Name').fill(PROJECT_NAME)
    await page.getByRole('button', { name: 'Create' }).click()
    await expect(page).toHaveURL(/\/projects\/e2e-confirm-\d+$/)

    await page.getByRole('link', { name: 'Tags', exact: true }).click()
    await page.getByLabel('Name').fill(TAG_NAME)
    await page.getByRole('button', { name: 'Create tag' }).click()
    await expect(page.getByText(TAG_NAME)).toBeVisible()
  })

  const dialog = page.getByRole('dialog')

  await test.step('вопрос виден, фокус внутри диалога, Esc отменяет', async () => {
    await page.getByRole('button', { name: 'Delete', exact: true }).click()

    await expect(dialog).toBeVisible()
    await expect(dialog.getByRole('heading', { name: 'Delete this tag?' })).toBeVisible()
    await expect(dialog.getByText('It will be removed from any tasks using it.')).toBeVisible()
    // Фокус на «Отмене»: первое же нажатие Enter не должно ничего удалять.
    await expect(dialog.getByRole('button', { name: 'Cancel' })).toBeFocused()

    // Фокус-трап. Tab ведёт к следующей кнопке того же диалога...
    await page.keyboard.press('Tab')
    await expect(dialog.getByRole('button', { name: 'Delete', exact: true })).toBeFocused()

    // ...а страница под ним инертна: даже прямой focus() на её кнопке ничего не даёт.
    // Это и есть причина, по которой взят нативный <dialog>, а не оверлей на div'ах: самодельная
    // ловушка фокуса так не умеет в принципе — она ловит Tab, а не запрещает фокус.
    const backgroundTookFocus = await page.evaluate(() => {
      const button = [...document.querySelectorAll('button')].find(
        (candidate) => candidate.textContent.trim() === 'Create tag',
      )
      button.focus()
      return document.activeElement === button
    })
    expect(backgroundTookFocus).toBe(false)

    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
    await expect(page.getByText(TAG_NAME)).toBeVisible()
  })

  await test.step('кнопка отмены — тоже отказ', async () => {
    await page.getByRole('button', { name: 'Delete', exact: true }).click()
    await dialog.getByRole('button', { name: 'Cancel' }).click()

    await expect(dialog).toHaveCount(0)
    await expect(page.getByText(TAG_NAME)).toBeVisible()
  })

  await test.step('согласие удаляет', async () => {
    await page.getByRole('button', { name: 'Delete', exact: true }).click()
    await dialog.getByRole('button', { name: 'Delete', exact: true }).click()

    await expect(dialog).toHaveCount(0)
    await expect(page.getByText(TAG_NAME)).toHaveCount(0)
    await expect(page.getByText('No tags yet')).toBeVisible()
  })

  await test.step('в вопросе про задачу перечислено, что уедет вместе с ней', async () => {
    await page.getByRole('link', { name: 'Kanban', exact: true }).click()
    await page.getByLabel('New task').fill(TASK_TITLE)
    await page.getByRole('button', { name: 'Add', exact: true }).click()
    await page.getByText(TASK_TITLE).click()
    await expect(page).toHaveURL(/\/tasks\/\d+$/)

    await page.getByPlaceholder('Subtask title').fill('A subtask of its own')
    await page.getByRole('button', { name: 'Add subtask' }).click()
    await expect(page.getByText('A subtask of its own')).toBeVisible()

    await page.getByPlaceholder('Write a comment...').fill('One comment for the record')
    await page.getByRole('button', { name: 'Send' }).click()
    await expect(page.getByText('One comment for the record')).toBeVisible()

    await page.getByRole('link', { name: 'Edit', exact: true }).click()
    await page.getByRole('button', { name: 'Delete task' }).click()

    await expect(dialog.getByRole('heading', { name: 'Delete this task?' })).toBeVisible()
    // Ровно то, ради чего вопрос и переписан: «удалить задачу» и «удалить задачу вместе с
    // подзадачей и перепиской» перестали выглядеть одинаково.
    await expect(dialog.getByText(/1 subtask and 1 comment/)).toBeVisible()

    await dialog.getByRole('button', { name: 'Cancel' }).click()
    await expect(page).toHaveURL(/\/tasks\/\d+\/edit$/)
  })
})
