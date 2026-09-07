import { expect, test } from '@playwright/test'
import { awaitVerificationLink } from './mailhog.js'

/**
 * Правка комментария (4.4) и @упоминания (4.5) через настоящий браузер.
 *
 * Проверяется здесь ровно то, чего не видят ни бэкенд-тесты, ни vitest: механика
 * набора. Автокомплит упоминаний — это textarea, каретка и всплывающий список, и
 * `MentionTextarea` целиком состоит из вещей, которые в jsdom либо не работают, либо
 * работают не так: `selectionStart`, `setSelectionRange`, `requestAnimationFrame`,
 * порядок `mousedown`/`blur`. Разбор строки покрыт юнит-тестами (`lib/mentions.test.js`),
 * права и уведомления — интеграционными; сюда вынесено то, что остаётся между ними.
 *
 * Второй участник живёт в своём browser context — и не ради изоляции сессий, а потому
 * что упоминание проверяется с той стороны, где оно и должно появиться: в колокольчике
 * у того, кого позвали.
 */

const RUN_ID = Date.now()

const AUTHOR = {
  email: `e2e-commenter-${RUN_ID}@example.com`,
  password: 'e2e-password-123',
  lastName: 'Playwright',
  firstName: 'Commenter',
}
const TEAMMATE = {
  email: `e2e-teammate-${RUN_ID}@example.com`,
  password: 'e2e-password-456',
  lastName: 'Playwright',
  firstName: 'Teammate',
}
const PROJECT_NAME = `E2E comments ${RUN_ID}`
const TASK_TITLE = `Discuss the mention flow ${RUN_ID}`

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

test('комментарии: @упоминание из автокомплита и правка своего текста', async ({ page, browser }) => {
  const teammateContext = await browser.newContext()
  const teammatePage = await teammateContext.newPage()

  let projectSlug = null

  await test.step('оба заводят аккаунты, второй становится участником проекта', async () => {
    await registerVerifyAndSignIn(teammatePage, TEAMMATE)
    await registerVerifyAndSignIn(page, AUTHOR)

    await expect(page.getByText("You don't have any projects yet")).toBeVisible()
    await page.getByRole('link', { name: 'New project' }).last().click()
    await page.getByLabel('Name').fill(PROJECT_NAME)
    await page.getByRole('button', { name: 'Create' }).click()
    await expect(page).toHaveURL(/\/projects\/e2e-comments-\d+$/)
    projectSlug = new URL(page.url()).pathname.split('/').pop()

    await page.goto(`/projects/${projectSlug}/settings/members`)
    await page.getByLabel('Email').fill(TEAMMATE.email)
    await page.getByRole('button', { name: 'Invite' }).click()
    // Аккаунт уже есть — значит участник добавляется сразу, без письма (4.2).
    await expect(page.getByText(`${TEAMMATE.email} has been added to the project`)).toBeVisible()
  })

  await test.step('создаётся задача', async () => {
    await page.getByRole('link', { name: 'Kanban' }).click()
    await page.getByLabel('New task').fill(TASK_TITLE)
    await page.getByRole('button', { name: 'Add', exact: true }).click()
    await page.getByText(TASK_TITLE).click()
    await expect(page).toHaveURL(/\/tasks\/\d+$/)
  })

  const commentBox = page.getByPlaceholder('Write a comment...')

  await test.step('@ открывает список участников, выбор подставляет адрес', async () => {
    await commentBox.click()
    // Печатаем по клавише: подсказки завязаны на позицию каретки, а fill() выставил бы
    // значение одним присваиванием — то есть проверял бы не тот путь, которым ходят люди.
    await commentBox.pressSequentially('Please take a look, @teammate')

    const suggestion = page.getByRole('option').filter({ hasText: TEAMMATE.email })
    await expect(suggestion).toBeVisible()
    await suggestion.click()

    // В поле оказывается адрес, а не имя: хранится упоминание именно так — это
    // единственная форма, которую разбирает сервер (см. MentionParser.java).
    await expect(commentBox).toHaveValue(`Please take a look, @${TEAMMATE.email} `)
  })

  await test.step('отправленное упоминание показано именем, а не адресом', async () => {
    await page.getByRole('button', { name: 'Send' }).click()

    // Адрес заменён на «@Фамилия Имя» — ради этого разбор на стороне отображения и нужен.
    await expect(page.getByText(`@${TEAMMATE.lastName} ${TEAMMATE.firstName}`)).toBeVisible()
    await expect(page.getByText('Please take a look,')).toBeVisible()
  })

  await test.step('упомянутый видит уведомление в колокольчике', async () => {
    await teammatePage.goto('/projects')
    await teammatePage.getByRole('button', { name: 'Notifications' }).click()

    await expect(teammatePage.getByText('mentioned you in task')).toBeVisible()
    await expect(teammatePage.getByText(TASK_TITLE)).toBeVisible()
  })

  await test.step('автор правит свой комментарий — появляется пометка «изменено»', async () => {
    await page.getByRole('button', { name: 'Edit' }).click()

    const editBox = page.getByPlaceholder('Write a comment...').first()
    await editBox.fill(`Please take a look today, @${TEAMMATE.email}`)
    await page.getByRole('button', { name: 'Save' }).click()

    await expect(page.getByText('(edited)')).toBeVisible()
    await expect(page.getByText('Please take a look today,')).toBeVisible()

    // Перезагрузка — главное здесь: без неё тест доказывал бы только то, что текст
    // поменялся в кэше react-query, а вопрос в том, доехал ли PATCH до базы.
    await page.reload()
    await expect(page.getByText('Please take a look today,')).toBeVisible()
    await expect(page.getByText('(edited)')).toBeVisible()
  })

  await test.step('упомянутого правка не зовёт повторно', async () => {
    await teammatePage.reload()
    await teammatePage.getByRole('button', { name: 'Notifications' }).click()

    // Одно уведомление, а не два: человека уже позвали этим комментарием, и исправленное
    // слово в нём — не повод звать заново (см. TaskCommentService.update).
    await expect(teammatePage.getByText('mentioned you in task')).toHaveCount(1)
  })

  await test.step('чужой комментарий править нечем', async () => {
    await teammatePage.goto(page.url().replace(/^https?:\/\/[^/]+/, ''))
    await expect(teammatePage.getByText('Please take a look today,')).toBeVisible()

    // Кнопки правки у чужого комментария нет вовсе; запрет держится на бэкенде
    // (NOT_COMMENT_AUTHOR), а интерфейс просто не предлагает того, чего нельзя.
    await expect(teammatePage.getByRole('button', { name: 'Edit' })).toHaveCount(0)
  })

  await teammateContext.close()
})
