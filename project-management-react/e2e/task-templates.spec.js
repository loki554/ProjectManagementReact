import { expect, test } from '@playwright/test'
import { awaitVerificationLink } from './mailhog.js'

/**
 * Шаблоны задач и чек-листы (4.13) через настоящий браузер.
 *
 * Что здесь проверяется и не проверяется больше нигде: шаблон заводится со страницы, кнопка
 * на которую стоит в сайдбаре; выбранный шаблон действительно заполняет форму новой задачи
 * (а не создаёт задачу за спиной у человека); чек-лист шаблона доезжает до задачи и
 * отмечается кликом; счётчик «сделано/всего» появляется в списке задач и меняется вместе с
 * галочкой. Права и копирование чек-листа покрыты TaskTemplateAndChecklistIntegrationTest,
 * а вот навигация, подстановка в форму и инвалидация кэша между тремя экранами — только
 * здесь.
 */

const RUN_ID = Date.now()

const USER = {
  email: `e2e-templates-${RUN_ID}@example.com`,
  username: `e2e-templates-${RUN_ID}`.slice(0, 30),
  password: 'e2e-password-123',
  lastName: 'Playwright',
  // Не 'Templates': имя попадает в подпись селекта исполнителя, и getByLabel('Template')
  // тогда находит два элемента.
  firstName: 'Tpl',
}
const PROJECT_NAME = `E2E templates ${RUN_ID}`
const TEMPLATE = 'Release'
const TEMPLATE_TASK_TITLE = 'Release 0.0'
const STEPS = ['Build the artifact', 'Deploy it', 'Post in the chat']

test('шаблон заполняет форму задачи и приносит с собой чек-лист', async ({ page }) => {
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

  await test.step('проект', async () => {
    await page.getByRole('link', { name: 'New project' }).last().click()
    await page.getByLabel('Name').fill(PROJECT_NAME)
    await page.getByRole('button', { name: 'Create' }).click()
    await expect(page).toHaveURL(/\/projects\/e2e-templates-\d+$/)
  })

  await test.step('шаблон заводится со страницы из сайдбара', async () => {
    await page.getByRole('link', { name: 'Templates', exact: true }).click()
    await expect(page).toHaveURL(/\/settings\/templates$/)
    await expect(page.getByText('No templates yet')).toBeVisible()

    await page.getByRole('button', { name: 'New template' }).click()
    await page.getByLabel('Template name').fill(TEMPLATE)
    await page.getByLabel('Task title').fill(TEMPLATE_TASK_TITLE)
    await page.getByLabel('Urgency').selectOption({ label: 'High' })
    for (const step of STEPS) {
      await page.getByLabel('Add step').fill(step)
      await page.getByRole('button', { name: 'Add step' }).click()
      await expect(page.getByText(step)).toBeVisible()
    }
    await page.getByRole('button', { name: 'Save' }).click()

    // Строка списка несёт то, ради чего шаблон и выбирают: как назовётся задача и сколько
    // в нём шагов.
    await expect(page.getByText(TEMPLATE, { exact: true })).toBeVisible()
    await expect(page.getByText(`${TEMPLATE_TASK_TITLE} · 3 steps`)).toBeVisible()
  })

  // Правка шаблона — отдельный шаг, потому что пункты чек-листа в списке шаблонов не
  // приезжают: форма правки ждёт их отдельным запросом, и именно здесь легко было бы
  // сохранить шаблон с пустым чек-листом, не дождавшись загрузки.
  await test.step('шаблон открывается на правку вместе со своими пунктами', async () => {
    await page.getByRole('button', { name: 'Edit' }).click()
    for (const step of STEPS) {
      await expect(page.getByText(step)).toBeVisible()
    }

    await page.getByLabel('Add step').fill('Tag the commit')
    await page.getByRole('button', { name: 'Add step' }).click()
    await page.getByRole('button', { name: 'Save' }).click()
    await expect(page.getByText(`${TEMPLATE_TASK_TITLE} · 4 steps`)).toBeVisible()

    // Лишний шаг был нужен только чтобы убедиться, что правка не теряет старые: убираем
    // его обратно, дальше сценарий рассчитывает на три.
    await page.getByRole('button', { name: 'Edit' }).click()
    await expect(page.getByText('Tag the commit')).toBeVisible()
    await page
      .getByRole('listitem')
      .filter({ hasText: 'Tag the commit' })
      .getByRole('button', { name: 'Remove step' })
      .click()
    await page.getByRole('button', { name: 'Save' }).click()
    await expect(page.getByText(`${TEMPLATE_TASK_TITLE} · 3 steps`)).toBeVisible()
  })

  await test.step('шаблон заполняет форму новой задачи, а не создаёт её сам', async () => {
    await page.getByRole('link', { name: 'Task list' }).click()
    await page.getByRole('link', { name: 'New task' }).click()
    await expect(page).toHaveURL(/\/tasks\/new$/)

    // Роль, а не getByLabel: у редактора описания есть кнопка «Insert title», и по
    // подписи «Title» их находится две.
    const titleInput = page.getByRole('textbox', { name: 'Title' })

    // До выбора шаблона форма пуста — подставляет её именно выбор.
    await expect(titleInput).toHaveValue('')
    // Подпись оборачивает <select> целиком, поэтому доступное имя поля — это «Template»
    // плюс тексты всех вариантов; отсюда регулярка по началу, а не точное совпадение.
    await page.getByLabel(/^Template/).selectOption({ label: TEMPLATE })

    await expect(titleInput).toHaveValue(TEMPLATE_TASK_TITLE)
    await expect(page.getByLabel('Urgency')).toHaveValue('HIGH')
    await expect(page.getByText('3 checklist steps will be added to the task')).toBeVisible()

    // Подставленное — обычное содержимое формы: правим его перед созданием.
    await titleInput.fill('Release 2.7')
    await page.getByRole('button', { name: 'Create task' }).click()
    await expect(page).toHaveURL(/\/tasks\/\d+$/)
  })

  await test.step('чек-лист доехал до задачи и отмечается кликом', async () => {
    for (const step of STEPS) {
      await expect(page.getByText(step)).toBeVisible()
    }
    await expect(page.getByText('0 of 3')).toBeVisible()

    // click, а не check: галочка контролируемая и перерисовывается после оптимистичного
    // обновления, из-за чего check() не находит свой прежний элемент, чтобы свериться.
    // Состояние проверяем отдельно — им и является прогресс.
    await page.getByRole('checkbox', { name: STEPS[0] }).click()
    await expect(page.getByText('1 of 3')).toBeVisible()
  })

  await test.step('свой пункт добавляется и переписывается', async () => {
    await page.getByLabel('Add', { exact: true }).fill('Check the logs')
    await page.getByRole('button', { name: 'Add', exact: true }).click()
    await expect(page.getByText('1 of 4')).toBeVisible()

    const row = page.getByRole('listitem').filter({ hasText: 'Check the logs' })
    await row.getByRole('button', { name: 'Edit' }).click()
    // Строка в режиме правки перестаёт содержать свой текст (он уехал в input), поэтому
    // дальше адресуемся к самому полю. Enter — тот же путь, которым правку и завершают.
    const editor = page.getByLabel('Step text')
    await editor.fill('Check the logs and metrics')
    await editor.press('Enter')
    await expect(page.getByText('Check the logs and metrics')).toBeVisible()
    // Переименование не снимает галочку с соседа и не меняет прогресс.
    await expect(page.getByText('1 of 4')).toBeVisible()
  })

  await test.step('прогресс виден в списке задач', async () => {
    await page.getByRole('link', { name: 'Task list' }).click()
    await expect(page.getByRole('row').filter({ hasText: 'Release 2.7' }).getByText('1/4')).toBeVisible()
  })
})
