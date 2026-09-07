import { expect, test } from '@playwright/test'
import { awaitVerificationLink } from './mailhog.js'

/**
 * Массовые операции над задачами (4.6) через настоящий браузер.
 *
 * Сюда вынесено ровно то, чего не видят ни интеграционные тесты бэкенда, ни vitest:
 * механика выделения. Само тело запроса собирает чистая функция и её покрывает
 * `lib/bulkTasks.test.js`, права и транзакционность — `TaskBulkUpdateIntegrationTest`.
 * А вот что галочка в строке не открывает задачу, что Shift+клик берёт диапазон, что
 * заголовочная галочка переключает всю страницу и что после «Применить» таблица
 * перечитывается с сервера — это события мыши, всплытие и инвалидация кэша, то есть
 * вещи, которых в jsdom либо нет, либо они ведут себя иначе.
 *
 * Задачи заводятся через UI доски (там это одна строка ввода), а проверяются на странице
 * списка: массовая правка живёт только там.
 */

const RUN_ID = Date.now()

const USER = {
  email: `e2e-bulk-${RUN_ID}@example.com`,
  username: `e2e-bulk-${RUN_ID}`.slice(0, 30),
  password: 'e2e-password-123',
  lastName: 'Playwright',
  firstName: 'Bulk',
}
const PROJECT_NAME = `E2E bulk ops ${RUN_ID}`
// Названия без слов-статусов внутри: строки таблицы ищутся по тексту, а «Done» в заголовке
// задачи сделал бы поиск неоднозначным.
const TASKS = ['Alpha task', 'Bravo task', 'Charlie task', 'Delta task']

/** Строка таблицы по названию задачи. */
function row(page, title) {
  return page.getByRole('row').filter({ hasText: title })
}

function checkboxIn(page, title) {
  return row(page, title).getByRole('checkbox')
}

test('массовые операции: выделение строк и правка выделенного одним запросом', async ({ page }) => {
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

  await test.step('проект и четыре задачи', async () => {
    await expect(page.getByText("You don't have any projects yet")).toBeVisible()
    await page.getByRole('link', { name: 'New project' }).last().click()
    await page.getByLabel('Name').fill(PROJECT_NAME)
    await page.getByRole('button', { name: 'Create' }).click()
    await expect(page).toHaveURL(/\/projects\/e2e-bulk-ops-\d+$/)

    await page.getByRole('link', { name: 'Kanban' }).click()
    for (const title of TASKS) {
      await page.getByLabel('New task').fill(title)
      await page.getByRole('button', { name: 'Add', exact: true }).click()
      await expect(page.getByText(title)).toBeVisible()
    }

    await page.getByRole('link', { name: 'Task list' }).click()
    await expect(page).toHaveURL(/\/tasks$/)
    await expect(page.getByText('Tasks: 4')).toBeVisible()
  })

  await test.step('галочка в строке выделяет задачу, а не открывает её', async () => {
    await checkboxIn(page, TASKS[0]).click()

    // Остались на списке: клик по галочке не должен был всплыть до строки, которая
    // навигирует на страницу задачи.
    await expect(page).toHaveURL(/\/tasks$/)
    await expect(page.getByText('Selected: 1')).toBeVisible()
  })

  await test.step('Shift+клик добавляет диапазон', async () => {
    // От первой (уже отмеченной) до третьей — вторая должна отметиться сама.
    await checkboxIn(page, TASKS[2]).click({ modifiers: ['Shift'] })

    await expect(page.getByText('Selected: 3')).toBeVisible()
    await expect(checkboxIn(page, TASKS[1])).toBeChecked()
    await expect(checkboxIn(page, TASKS[3])).not.toBeChecked()
  })

  await test.step('правка применяется ко всему выделению и переживает перезагрузку', async () => {
    await page.getByLabel('New status').selectOption('DONE')
    await page.getByLabel('New assignee').selectOption({ label: `${USER.lastName} ${USER.firstName}` })
    await page.getByRole('button', { name: 'Apply' }).click()

    await expect(page.getByText('Tasks updated: 3')).toBeVisible()
    // Успех снимает выделение — панель уходит вместе с ним.
    await expect(page.getByText('Selected: 3')).toHaveCount(0)

    // Перезагрузка — главное здесь: без неё тест доказывал бы только то, что таблица
    // перерисовалась, а вопрос в том, доехал ли PATCH до базы.
    await page.reload()
    for (const title of TASKS.slice(0, 3)) {
      await expect(row(page, title).getByText('Done')).toBeVisible()
      await expect(row(page, title).getByText(`${USER.lastName} ${USER.firstName}`)).toBeVisible()
    }
    // Четвёртая не выделялась — и не изменилась.
    await expect(row(page, TASKS[3]).getByText('New', { exact: true })).toBeVisible()
  })

  await test.step('заголовочная галочка выделяет и снимает всю страницу', async () => {
    const selectAll = page.getByLabel('Select all tasks on this page')

    await selectAll.click()
    await expect(page.getByText('Selected: 4')).toBeVisible()

    await selectAll.click()
    await expect(page.getByText('Selected: 4')).toHaveCount(0)
  })

  await test.step('снятие поля флагом, а не пустым значением', async () => {
    await page.getByLabel('Select all tasks on this page').click()
    await page.getByLabel('New assignee').selectOption({ label: 'Remove assignee' })
    await page.getByRole('button', { name: 'Apply' }).click()

    // Изменились три из четырёх: у Delta исполнителя и так не было.
    await expect(page.getByText('Tasks updated: 3')).toBeVisible()

    await page.reload()
    for (const title of TASKS) {
      await expect(row(page, title).getByText('Unassigned')).toBeVisible()
    }
  })

  await test.step('смена фильтра сбрасывает выделение', async () => {
    await checkboxIn(page, TASKS[0]).click()
    await expect(page.getByText('Selected: 1')).toBeVisible()

    // Состав страницы после фильтра другой, и выделение, пережившее его, применилось бы
    // к задачам, которых человек уже не видит.
    await page.getByPlaceholder('Search by title...').fill(TASKS[3])
    await expect(page.getByText('Selected: 1')).toHaveCount(0)
  })
})
