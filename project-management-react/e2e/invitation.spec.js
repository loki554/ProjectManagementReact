import { expect, test } from '@playwright/test'
import { awaitInvitationLink, awaitVerificationLink } from './mailhog.js'

/**
 * Приглашение незарегистрированного (4.2) целиком, через браузер: администратор зовёт по
 * адресу, которого нет в системе → письмо → страница приглашения → регистрация с уже
 * подставленным адресом → подтверждение почты → человек внутри проекта.
 *
 * Это и есть тот стык, который не проверяет ничто другое. Бэкенд-тесты знают, что
 * приглашение принимается по `EmailVerifiedEvent`; vitest знает, что форма рисуется. Но
 * доехал ли токен из письма в query-параметр, подставился ли адрес в форму регистрации,
 * не потерялся ли инвайт по дороге через две страницы — видно только здесь.
 *
 * Приглашённый живёт в отдельном browser context, а не в той же вкладке: у него не должно
 * быть ни сессии администратора, ни его localStorage — ровно как у человека, который открыл
 * ссылку из письма у себя.
 */

const RUN_ID = Date.now()

const ADMIN = {
  email: `e2e-inviter-${RUN_ID}@example.com`,
  password: 'e2e-password-123',
  lastName: 'Playwright',
  firstName: 'Inviter',
}
const INVITEE = {
  email: `e2e-invitee-${RUN_ID}@example.com`,
  password: 'e2e-password-456',
  lastName: 'Playwright',
  firstName: 'Invitee',
}
const PROJECT_NAME = `E2E invitation ${RUN_ID}`

async function registerVerifyAndSignIn(page, user) {
  await page.goto('/register')
  await page.getByLabel('Email').fill(user.email)
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

test('приглашение по email: письмо → страница приглашения → регистрация → участник проекта', async ({
  page,
  browser,
}) => {
  let projectSlug = null

  await test.step('администратор заводит проект', async () => {
    await registerVerifyAndSignIn(page, ADMIN)

    await expect(page.getByText("You don't have any projects yet")).toBeVisible()
    await page.getByRole('link', { name: 'New project' }).last().click()
    await page.getByLabel('Name').fill(PROJECT_NAME)
    await page.getByRole('button', { name: 'Create' }).click()

    await expect(page).toHaveURL(/\/projects\/e2e-invitation-\d+$/)
    projectSlug = new URL(page.url()).pathname.split('/').pop()
  })

  await test.step('приглашает адрес, которого нет в системе', async () => {
    await page.goto(`/projects/${projectSlug}/settings/members`)

    await page.getByLabel('Email').fill(INVITEE.email)
    await page.getByRole('button', { name: 'Invite' }).click()

    // Исход приглашения зависит от того, есть ли у адресата аккаунт, — и страница обязана
    // это проговорить. «Отправлено письмо» вместо «добавлен» здесь и есть проверка того,
    // что сервер ушёл в ветку приглашения, а не молча ничего не сделал.
    await expect(page.getByText(`An invitation was sent to ${INVITEE.email}`)).toBeVisible()

    // Непринятое приглашение видно в своём блоке — с ролью и без пометки «expired».
    const pending = page.getByRole('listitem').filter({ hasText: INVITEE.email })
    await expect(pending).toBeVisible()
    await expect(pending).toContainText('Member')
    await expect(pending).not.toContainText('expired')
  })

  const invitee = await browser.newContext()
  const inviteePage = await invitee.newPage()

  await test.step('приглашённый открывает ссылку из письма', async () => {
    await inviteePage.goto(await awaitInvitationLink(INVITEE.email))

    await expect(inviteePage.getByRole('heading', { name: 'Project invitation' })).toBeVisible()
    await expect(inviteePage.getByText(PROJECT_NAME)).toBeVisible()
    // Адрес и роль — то же, что в письме; ничего сверх этого страница анониму не показывает.
    await expect(inviteePage.getByText(INVITEE.email)).toBeVisible()
    await expect(inviteePage.getByText('Member', { exact: true })).toBeVisible()
  })

  await test.step('регистрируется по приглашению — адрес подставлен и не редактируется', async () => {
    await inviteePage.getByRole('link', { name: 'Create an account' }).click()
    await expect(inviteePage).toHaveURL(/\/register\?invite=/)

    const email = inviteePage.getByLabel('Email')
    // Приглашение адресное: регистрация на другой адрес его просто не примет, и узнал бы
    // человек об этом только после подтверждения почты. Поэтому поле readonly.
    await expect(email).toHaveValue(INVITEE.email)
    await expect(email).toHaveAttribute('readonly', '')

    await inviteePage.getByLabel('Password').fill(INVITEE.password)
    await inviteePage.getByLabel('Last name').fill(INVITEE.lastName)
    await inviteePage.getByLabel('First name').fill(INVITEE.firstName)
    await inviteePage.getByRole('button', { name: 'Sign up' }).click()

    await expect(inviteePage.getByText(`you will land in "${PROJECT_NAME}"`)).toBeVisible()
  })

  await test.step('после подтверждения почты оказывается в проекте', async () => {
    await inviteePage.goto(await awaitVerificationLink(INVITEE.email))
    await inviteePage.getByRole('link', { name: 'Go to sign in' }).click()

    await inviteePage.getByLabel('Email').fill(INVITEE.email)
    await inviteePage.getByLabel('Password').fill(INVITEE.password)
    await inviteePage.getByRole('button', { name: 'Sign in' }).click()

    // Проект появился в списке сам — по приглашению, без единого клика «принять».
    await expect(inviteePage).toHaveURL(/\/projects$/)
    await expect(inviteePage.getByText(PROJECT_NAME)).toBeVisible()
  })

  await test.step('у администратора он стал участником, приглашение исчезло', async () => {
    await page.goto(`/projects/${projectSlug}/settings/members`)

    await expect(page.getByText(INVITEE.email)).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Invitations' })).toHaveCount(0)
  })

  await invitee.close()
})
