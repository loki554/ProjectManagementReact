import { expect, test } from '@playwright/test'
import { awaitVerificationLink } from './mailhog.js'

/**
 * Спринты (4.9) через настоящий браузер.
 *
 * Здесь проверяется то, чего не видит ничто другое. Арифметику прогресса и сроков
 * покрывает `lib/sprints.test.js`, права, переходы статусов и переезд недоделанных задач —
 * `SprintIntegrationTest`. А вот что спринт заводится с той страницы, кнопка на которую
 * стоит в сайдбаре; что задача перекладывается между бэклогом и спринтом одним нажатием и
 * это видно сразу в обеих колонках; что фильтр по спринту в списке задач показывает именно
 * его состав; и что закрытие спринта действительно уносит недоделанное в следующий — это
 * навигация, инвалидация кэша и диалоги, то есть то, чего в jsdom нет.
 *
 * Диалог подтверждения ловится одним обработчиком на весь сценарий, а не `page.once` перед
 * каждым шагом: одноразовые обработчики накапливаются, если ожидаемый диалог не появился, и
 * следующий достаётся сразу двоим (та же ловушка, что в dependencies.spec.js). Счётчик
 * диалогов заодно становится проверкой: спрашивают ровно там, где должны.
 */

const RUN_ID = Date.now()

const USER = {
  email: `e2e-sprints-${RUN_ID}@example.com`,
  username: `e2e-sprints-${RUN_ID}`.slice(0, 30),
  password: 'e2e-password-123',
  lastName: 'Playwright',
  firstName: 'Sprints',
}
const PROJECT_NAME = `E2E planning ${RUN_ID}`
const SPRINT = 'Sprint one'
const NEXT_SPRINT = 'Sprint two'
const TASKS = ['Ship the API', 'Ship the screen', 'Write the docs']

// Карточка спринта — обычный div без своей роли, и надёжно адресуется не она сама, а то,
// что в ней уникально: полоса прогресса «закрыто/всего» и кнопки жизненного цикла (кнопка
// «Complete» есть только у идущего спринта, «Start» — только у запланированного).
function progress(page, closed, total) {
  return page.getByText(`${closed}/${total}`)
}

function taskRow(page, title) {
  return page.getByRole('listitem').filter({ hasText: title })
}

test('спринт наполняется из бэклога, показывает прогресс и уносит недоделанное в следующий', async ({ page }) => {
  // Завершение спринта спрашивает диалогом на странице (5.2). Отдельно считать вопросы не
  // нужно: пока диалог открыт, страница под ним инертна — незакрытый вопрос сломал бы
  // следующий шаг сценария сам.
  const dialog = page.getByRole('dialog')

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

  await test.step('проект и три задачи в бэклоге', async () => {
    await page.getByRole('link', { name: 'New project' }).last().click()
    await page.getByLabel('Name').fill(PROJECT_NAME)
    await page.getByRole('button', { name: 'Create' }).click()
    await expect(page).toHaveURL(/\/projects\/e2e-planning-\d+$/)

    await page.getByRole('link', { name: 'Kanban' }).click()
    for (const title of TASKS) {
      await page.getByLabel('New task').fill(title)
      await page.getByRole('button', { name: 'Add', exact: true }).click()
      await expect(page.getByText(title)).toBeVisible()
    }
  })

  // Кнопка в сайдбаре — часть пункта: спринты это рабочий экран, а не раздел настроек,
  // и попасть на него нужно оттуда же, откуда на доску и список.
  await test.step('страница спринтов открывается из сайдбара', async () => {
    await page.getByRole('link', { name: 'Sprints', exact: true }).click()
    await expect(page).toHaveURL(/\/sprints$/)
    await expect(page.getByText('No sprints yet')).toBeVisible()
    // Все три задачи — в бэклоге: спринтов ещё нет, значит вне спринта они все.
    for (const title of TASKS) {
      await expect(taskRow(page, title)).toBeVisible()
    }
  })

  await test.step('спринт заводится и появляется запланированным', async () => {
    await page.getByRole('button', { name: '+ New sprint' }).click()
    await page.getByLabel('Name').fill(SPRINT)
    await page.getByLabel('Goal').fill('Get the thing out')
    await page.getByLabel('Start').fill('2026-09-07')
    await page.getByLabel('End').fill('2026-09-18')
    await page.getByRole('button', { name: 'New sprint' }).last().click()

    await expect(page.getByText(SPRINT).first()).toBeVisible()
    await expect(page.getByText('Planned')).toBeVisible()
    await expect(page.getByText('No tasks in this sprint yet')).toBeVisible()
  })

  await test.step('задачи переезжают из бэклога в спринт одним нажатием', async () => {
    for (const title of TASKS.slice(0, 2)) {
      await taskRow(page, title).getByRole('button', { name: 'To sprint' }).click()
      // Обе колонки читают один и тот же список задач с разными фильтрами: задача обязана
      // исчезнуть из бэклога и появиться в спринте, а не остаться в обеих сразу.
      await expect(taskRow(page, title).getByRole('button', { name: 'To backlog' })).toBeVisible()
    }
    await expect(progress(page, 0, 2)).toBeVisible()
    await expect(taskRow(page, TASKS[2]).getByRole('button', { name: 'To sprint' })).toBeVisible()
  })

  await test.step('спринт начинается и становится текущим', async () => {
    await page.getByRole('button', { name: 'Start' }).click()
    await expect(page.getByText('In progress')).toBeVisible()
  })

  await test.step('фильтр списка задач показывает состав спринта', async () => {
    await page.getByRole('link', { name: 'Task list' }).click()
    await page.getByRole('combobox').filter({ hasText: 'All sprints' }).selectOption({ label: SPRINT })

    await expect(page).toHaveURL(/[?&]sprint=[0-9a-f-]{36}/)
    await expect(page.getByText('Tasks: 2')).toBeVisible()
    await expect(page.getByRole('row').filter({ hasText: TASKS[2] })).toHaveCount(0)
  })

  await test.step('закрытая задача поднимает прогресс спринта', async () => {
    await page.getByRole('row').filter({ hasText: TASKS[0] }).getByRole('checkbox').click()
    await page.getByLabel('New status').selectOption({ label: 'Done' })
    await page.getByRole('button', { name: 'Apply' }).click()
    await expect(page.getByText('Tasks updated: 1')).toBeVisible()

    await page.getByRole('link', { name: 'Sprints', exact: true }).click()
    await expect(progress(page, 1, 2)).toBeVisible()
  })

  await test.step('закрытие спринта уносит недоделанное в следующий', async () => {
    await page.getByRole('button', { name: '+ New sprint' }).click()
    await page.getByLabel('Name').fill(NEXT_SPRINT)
    await page.getByLabel('Start').fill('2026-09-21')
    await page.getByLabel('End').fill('2026-10-02')
    await page.getByRole('button', { name: 'New sprint' }).last().click()
    await expect(page.getByText(NEXT_SPRINT).first()).toBeVisible()

    // Кнопка «Complete» одна на странице: она есть только у идущего спринта.
    await page.getByRole('button', { name: 'Complete' }).click()

    // Вопрос про то, куда девать незакрытое, — а не «точно ли». И у него три ответа:
    // перенести, вернуть в бэклог и не завершать вовсе (5.2).
    await expect(dialog.getByRole('heading', { name: 'Complete this sprint?' })).toBeVisible()
    await expect(dialog.getByText(/1 task is still open/)).toBeVisible()
    await expect(dialog.getByRole('button', { name: 'Cancel' })).toBeVisible()
    await dialog.getByRole('button', { name: `Move to "${NEXT_SPRINT}"` }).click()
    await expect(page.getByText('Sprint completed, 1 task moved')).toBeVisible()
    // exact: тост «Sprint completed, 1 task moved» тоже содержит это слово.
    await expect(page.getByText('Completed', { exact: true })).toBeVisible()
  })

  await test.step('переехавшая задача лежит в следующем спринте, закрытая осталась в прошлом', async () => {
    await page.getByText(NEXT_SPRINT).first().click()
    await expect(taskRow(page, TASKS[1])).toBeVisible()

    await page.getByText(SPRINT).first().click()
    await expect(taskRow(page, TASKS[0])).toBeVisible()
    // Из завершённого спринта вынимать нечего: его состав — это история.
    await expect(taskRow(page, TASKS[0]).getByRole('button', { name: 'To backlog' })).toHaveCount(0)
  })

  // Ни заведение спринта, ни его старт, ни перекладывание задач ничего не спрашивают:
  // будь иначе, открытый диалог заблокировал бы страницу и следующий шаг не прошёл бы.
  await expect(dialog).toHaveCount(0)
})
