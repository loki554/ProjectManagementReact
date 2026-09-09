import { expect, test } from '@playwright/test'
import { awaitVerificationLink } from './mailhog.js'

/**
 * Зависимости между задачами (4.8) через настоящий браузер.
 *
 * Сюда вынесено то, чего не видят ни `TaskDependencyIntegrationTest` (там HTTP без
 * интерфейса), ни vitest (там jsdom и замоканная сеть): связь заводится номером задачи,
 * который человек читает с соседней карточки; она сразу видна с обратной стороны, то есть
 * кэш обеих страниц действительно инвалидируется; признак «заблокирована» доезжает до
 * таблицы и до доски; и главное — предупреждение при закрытии, которое существует ровно
 * как диалог: сервер отвечает 409, страница спрашивает, человек соглашается, и тот же
 * запрос уходит повторно. Ни одного из этих шагов по отдельности не хватает, чтобы
 * убедиться, что предупреждение работает как предупреждение, а не как запрет.
 */

// Шесть колонок канбана по 256px в ширину не помещаются в стандартные 1280 — доска
// начинает скроллиться по горизонтали, а dnd-kit во время перетаскивания доскролливает её
// сам, из-за чего координаты целевой колонки, снятые до старта, устаревают, и карточка
// уезжает в соседнюю. В остальных сценариях это не мешает, здесь же перетаскивание — часть
// проверки, поэтому окно берётся такое, в которое доска влезает целиком.
test.use({ viewport: { width: 1800, height: 900 } })

const RUN_ID = Date.now()

const USER = {
  email: `e2e-deps-${RUN_ID}@example.com`,
  username: `e2e-deps-${RUN_ID}`.slice(0, 30),
  password: 'e2e-password-123',
  lastName: 'Playwright',
  firstName: 'Deps',
}
const PROJECT_NAME = `E2E dependencies ${RUN_ID}`
// Названия без слов-статусов внутри: строки и карточки ищутся по видимому тексту.
const BLOCKER = 'Design the schema'
const BLOCKED = 'Build the screen'
const SECOND = 'Write the guide'

function row(page, title) {
  return page.getByRole('row').filter({ hasText: title })
}

function column(page, statusLabel) {
  return page.locator('.min-w-64').filter({ has: page.getByText(statusLabel, { exact: true }) })
}

/**
 * Перетаскивание карточки — тот же приём, что в golden-path.spec.js: dnd-kit слушает
 * pointer-события и не начинает drag, пока курсор не сдвинулся на порог активации,
 * поэтому мышь ведётся по шагам, а не телепортируется в цель.
 */
async function dragCardInto(page, card, targetColumn) {
  const from = await card.boundingBox()
  const to = await targetColumn.boundingBox()

  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2)
  await page.mouse.down()
  await page.mouse.move(from.x + from.width / 2 + 20, from.y + from.height / 2, { steps: 5 })
  await page.mouse.move(to.x + to.width / 2, to.y + 100, { steps: 20 })
  await page.mouse.up()
}

test('зависимости: связь номером, признак «заблокирована» и предупреждение при закрытии', async ({ page }) => {
  let blockerNumber
  let blockedNumber
  let secondNumber
  let projectSlug

  // Предупреждение о блокерах спрашивают диалогом на самой странице (5.2), а не нативным
  // confirm. Считать вопросы обработчиком больше не нужно: пока диалог открыт, страница под
  // ним инертна, поэтому лишний незакрытый вопрос немедленно ломает следующий же шаг.
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

  await test.step('проект и две задачи', async () => {
    await page.getByRole('link', { name: 'New project' }).last().click()
    await page.getByLabel('Name').fill(PROJECT_NAME)
    await page.getByRole('button', { name: 'Create' }).click()
    // Slug генерирует сервер — забираем его из адреса, а не собираем из названия.
    await expect(page).toHaveURL(/\/projects\/e2e-dependencies-\d+$/)
    projectSlug = new URL(page.url()).pathname.split('/').pop()

    await page.getByRole('link', { name: 'Kanban' }).click()
    for (const title of [BLOCKER, BLOCKED, SECOND]) {
      await page.getByLabel('New task').fill(title)
      await page.getByRole('button', { name: 'Add', exact: true }).click()
      await expect(page.getByText(title)).toBeVisible()
    }

    // Номера присваивает сервер, и связь заводится именно ими — читаем их с доски так же,
    // как это делает человек.
    await page.getByRole('link', { name: 'Task list' }).click()
    blockerNumber = (await row(page, BLOCKER).getByText(/^#\d+$/).innerText()).slice(1)
    blockedNumber = (await row(page, BLOCKED).getByText(/^#\d+$/).innerText()).slice(1)
    secondNumber = (await row(page, SECOND).getByText(/^#\d+$/).innerText()).slice(1)
  })

  await test.step('связь заводится номером задачи', async () => {
    await page.getByRole('row').filter({ hasText: BLOCKED }).click()
    await expect(page.getByRole('heading', { name: BLOCKED })).toBeVisible()

    await page.getByLabel('Number of the task that blocks this one').fill(blockerNumber)
    await page.getByRole('button', { name: 'Link' }).first().click()

    await expect(page.getByRole('link', { name: `#${blockerNumber} ${BLOCKER}` })).toBeVisible()
    // Признак «закрывать рано» появляется рядом со статусом, а не только в панели.
    await expect(page.getByText('1 open blocker')).toBeVisible()
  })

  await test.step('связь видна с обратной стороны', async () => {
    await page.getByRole('link', { name: `#${blockerNumber} ${BLOCKER}` }).click()
    await expect(page.getByRole('heading', { name: BLOCKER })).toBeVisible()

    // У блокера та же связь читается как «блокирует», и заводить её второй раз не нужно.
    await expect(page.getByRole('link', { name: `#${blockedNumber} ${BLOCKED}` })).toBeVisible()
    await expect(page.getByText('open blocker')).toHaveCount(0)
  })

  await test.step('признак заблокированности доезжает до таблицы', async () => {
    await page.getByRole('link', { name: 'Task list' }).click()
    await expect(row(page, BLOCKED).getByLabel('1 open blocker')).toBeVisible()
    await expect(row(page, BLOCKER).getByLabel('1 open blocker')).toHaveCount(0)
  })

  await test.step('доска: перетаскивание в «Выполнено» спрашивает, отказ возвращает карточку', async () => {
    // Второй задаче тот же блокер — она понадобится доске и массовой правке, а BLOCKED
    // уедет в DONE через форму задачи ниже.
    await page.goto(`/projects/${projectSlug}/tasks/${secondNumber}`)
    await page.getByLabel('Number of the task that blocks this one').fill(blockerNumber)
    await page.getByRole('button', { name: 'Link' }).first().click()
    await expect(page.getByText('1 open blocker')).toBeVisible()

    await page.getByRole('link', { name: 'Kanban' }).click()
    await dragCardInto(page, column(page, 'New').getByText(SECOND), column(page, 'Done'))

    // Спросили — и после отказа карточка вернулась в свою колонку: оптимистичный перенос
    // откатился вместе с отклонённым запросом.
    await expect(dialog.getByRole('heading', { name: 'Mark this task as done?' })).toBeVisible()
    await expect(dialog.getByText('It still has open blockers.')).toBeVisible()
    await dialog.getByRole('button', { name: 'Cancel' }).click()
    await expect(column(page, 'New').getByText(SECOND)).toBeVisible()
    await page.reload()
    await expect(column(page, 'New').getByText(SECOND)).toBeVisible()
  })

  await test.step('массовая правка спрашивает и называет заблокированные задачи', async () => {
    await page.getByRole('link', { name: 'Task list' }).click()
    await row(page, SECOND).getByRole('checkbox').click()
    await page.getByLabel('New status').selectOption('DONE')

    // Здесь вопрос задаётся до запроса и перечисляет номера: на двадцати выделенных
    // задачах важно, какие именно из них закрывать рано.
    await page.getByRole('button', { name: 'Apply' }).click()
    await expect(dialog.getByRole('heading', { name: 'Close the selected tasks?' })).toBeVisible()
    await expect(dialog.getByText(`#${secondNumber}`, { exact: false })).toBeVisible()
    await dialog.getByRole('button', { name: 'Cancel' }).click()
    await expect(row(page, SECOND).getByText('New', { exact: true })).toBeVisible()

    await page.getByRole('button', { name: 'Apply' }).click()
    await dialog.getByRole('button', { name: 'Close anyway' }).click()
    await expect(page.getByText('Tasks updated: 1')).toBeVisible()
    await page.reload()
    await expect(row(page, SECOND).getByText('Done', { exact: true })).toBeVisible()
  })

  await test.step('закрытие заблокированной задачи спрашивает — и отказ ничего не меняет', async () => {
    await page.goto(`/projects/${projectSlug}/tasks/${blockedNumber}/edit`)
    // Сервер отклоняет первый запрос, страница спрашивает — отказываемся, второго не будет.
    await page.getByLabel('Status').selectOption('DONE')
    await page.getByRole('button', { name: 'Save' }).click()

    // Спросили — и текст про блокеры, а не про что-нибудь ещё.
    await expect(dialog.getByRole('heading', { name: 'Mark this task as done?' })).toBeVisible()
    await expect(dialog.getByText('It still has open blockers.')).toBeVisible()
    await dialog.getByRole('button', { name: 'Cancel' }).click()
    await expect(dialog).toHaveCount(0)

    // Остались в форме (успех уводит на страницу задачи), и статус в базе прежний.
    await expect(page).toHaveURL(/\/edit$/)
    await page.goto(`/projects/${projectSlug}/tasks`)
    await expect(row(page, BLOCKED).getByText('New', { exact: true })).toBeVisible()
  })

  await test.step('согласие проводит ту же правку', async () => {
    await page.goto(`/projects/${projectSlug}/tasks/${blockedNumber}/edit`)
    await page.getByLabel('Status').selectOption('DONE')
    await page.getByRole('button', { name: 'Save' }).click()
    await dialog.getByRole('button', { name: 'Mark as done' }).click()

    // Ушли на страницу задачи — значит повторный запрос с ignoreBlockers прошёл.
    await expect(page).toHaveURL(new RegExp(`/tasks/${blockedNumber}$`))
    await page.goto(`/projects/${projectSlug}/tasks`)
    await expect(row(page, BLOCKED).getByText('Done', { exact: true })).toBeVisible()
    // Задача закрыта — признак «заблокирована» с неё уходит: теперь это история.
    await expect(row(page, BLOCKED).getByLabel('1 open blocker')).toHaveCount(0)
  })

  await test.step('снятая связь убирает признак заблокированности', async () => {
    await page.goto(`/projects/${projectSlug}/tasks/${blockedNumber}`)
    await page.getByRole('button', { name: 'Remove link' }).first().click()

    await expect(page.getByRole('link', { name: `#${blockerNumber} ${BLOCKER}` })).toHaveCount(0)
    await expect(page.getByText('Nothing is in the way')).toBeVisible()

    // С обратной стороны исчезла та же самая связь, и только она: вторую задачу тот же
    // блокер по-прежнему блокирует.
    await page.goto(`/projects/${projectSlug}/tasks/${blockerNumber}`)
    await expect(page.getByRole('link', { name: `#${blockedNumber} ${BLOCKED}` })).toHaveCount(0)
    await expect(page.getByRole('link', { name: `#${secondNumber} ${SECOND}` })).toBeVisible()
  })
})
