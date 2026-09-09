import { expect, test } from '@playwright/test'
import { awaitVerificationLink } from './mailhog.js'

/**
 * Живые обновления (4.15) и режим уведомлений по проекту (4.16) — через два настоящих
 * браузера.
 *
 * Здесь этот пункт и живёт целиком. Всё, что можно проверить с одной стороны, проверено
 * бэкенд-тестами (кому доходит сигнал, кому нет, что в нём нет данных) и vitest'ом (разбор
 * кадров, отображение «событие → что перечитать»). Но сам пункт формулируется как «у двоих
 * открытых пользователей канбан расходится до перезагрузки», а разойтись он может только
 * между двумя браузерами: нужны два соединения, две сессии react-query и настоящий SSE
 * поверх настоящего HTTP. Ни один из способов дешевле этого не покрывает.
 *
 * Второй участник живёт в отдельном browser context, а не во второй вкладке того же: своё
 * эхо клиент отфильтровывает по actorId (см. useRealtimeUpdates), и вторая вкладка того же
 * человека доказывала бы не то, что надо.
 *
 * Отрицательных проверок («выключил проект — ничего не пришло») здесь нет намеренно: ждать
 * отсутствия события в браузере значит либо ждать долго, либо получить тест, который иногда
 * зелёный. Режим MUTED проверяется там, где отсутствие видно точно, —
 * ProjectNotificationSettingsIntegrationTest.
 */

const RUN_ID = Date.now()

const OWNER = {
  email: `e2e-live-owner-${RUN_ID}@example.com`,
  username: `e2e-lo-${RUN_ID}`.slice(0, 30),
  password: 'e2e-password-123',
  lastName: 'Playwright',
  firstName: 'Owner',
}
const MEMBER = {
  email: `e2e-live-member-${RUN_ID}@example.com`,
  username: `e2e-lm-${RUN_ID}`.slice(0, 30),
  password: 'e2e-password-456',
  lastName: 'Playwright',
  firstName: 'Member',
}
const PROJECT_NAME = `E2E live ${RUN_ID}`
const FIRST_TASK = 'Card that must appear by itself'
const SECOND_TASK = 'Card that must ring the bell'

async function registerVerifyAndSignIn(page, user) {
  await page.goto('/register')
  await page.getByLabel('Email').fill(user.email)
  await page.getByLabel('Username').fill(user.username)
  await page.getByLabel('Password').fill(user.password)
  await page.getByLabel('Last name').fill(user.lastName)
  await page.getByLabel('First name').fill(user.firstName)
  await page.getByRole('button', { name: 'Sign up' }).click()
  await expect(page.getByText('Check your email')).toBeVisible()

  await page.goto(await awaitVerificationLink(user.email))
  await page.getByRole('link', { name: 'Go to sign in' }).click()

  await page.getByLabel('Email').fill(user.email)
  await page.getByLabel('Password').fill(user.password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).toHaveURL(/\/projects$/)
}

async function addTaskOnBoard(page, title) {
  await page.getByLabel('New task').fill(title)
  await page.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(page.getByText(title)).toBeVisible()
}

test('канбан и колокольчик соседа обновляются сами, без перезагрузки', async ({ page, browser }) => {
  let projectSlug = null

  await test.step('владелец заводит проект', async () => {
    await registerVerifyAndSignIn(page, OWNER)

    await page.getByRole('link', { name: 'New project' }).last().click()
    await page.getByLabel('Name').fill(PROJECT_NAME)
    await page.getByRole('button', { name: 'Create' }).click()

    await expect(page).toHaveURL(/\/projects\/e2e-live-\d+$/)
    projectSlug = new URL(page.url()).pathname.split('/').pop()
  })

  const memberContext = await browser.newContext()
  const memberPage = await memberContext.newPage()

  await test.step('второй участник заводит аккаунт и входит', async () => {
    await registerVerifyAndSignIn(memberPage, MEMBER)
  })

  await test.step('владелец добавляет его в проект', async () => {
    await page.goto(`/projects/${projectSlug}/settings/members`)
    await page.getByLabel('Email').fill(MEMBER.email)
    await page.getByRole('button', { name: 'Invite' }).click()

    // Аккаунт у адресата уже есть — значит его добавляют сразу, без письма и ссылки.
    await expect(page.getByText(`${MEMBER.email} has been added to the project`)).toBeVisible()
  })

  await test.step('оба смотрят на канбан', async () => {
    await memberPage.goto(`/projects/${projectSlug}/board`)
    await expect(memberPage.getByRole('link', { name: 'Kanban' })).toBeVisible()

    await page.goto(`/projects/${projectSlug}/board`)
  })

  await test.step('карточка, заведённая владельцем, появляется у соседа сама', async () => {
    await addTaskOnBoard(page, FIRST_TASK)

    // Ни перезагрузки, ни перехода, ни клика — именно это и есть проверяемое поведение.
    // Ожидание щедрое: сигнал уезжает после коммита, клиент копит события 300 мс и только
    // потом идёт перечитывать доску.
    await expect(memberPage.getByText(FIRST_TASK)).toBeVisible({ timeout: 20_000 })
  })

  await test.step('участник просит присылать всё по проекту', async () => {
    await memberPage.goto(`/projects/${projectSlug}`)

    const mode = memberPage.getByLabel('Project notifications')
    await expect(mode).toHaveValue('PARTICIPATING')
    await mode.selectOption('ALL')
    await expect(memberPage.getByText('New tasks and every comment in the project')).toBeVisible()

    // Настройка обязана пережить перезагрузку: иначе это переключатель, который ничего не
    // сохраняет, а выглядит одинаково.
    await memberPage.reload()
    await expect(memberPage.getByLabel('Project notifications')).toHaveValue('ALL')
  })

  await test.step('следующая чужая задача звонит в колокольчик, пока страница открыта', async () => {
    await addTaskOnBoard(page, SECOND_TASK)

    const bell = memberPage.getByRole('button', { name: 'Notifications' })
    await expect(bell).toContainText('1', { timeout: 20_000 })

    await bell.click()
    await expect(memberPage.getByText(`created task "${SECOND_TASK}"`)).toBeVisible()
  })

  await memberContext.close()
})
