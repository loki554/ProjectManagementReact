import { expect, test } from '@playwright/test'
import { awaitVerificationLink } from './mailhog.js'

/**
 * «Золотой путь» продукта одним сценарием: зарегистрироваться → подтвердить почту → войти →
 * завести проект → создать задачу → перетащить её на канбане → списать время.
 *
 * Ровно эта последовательность до сих пор проверялась только руками в браузере после каждой
 * заметной правки. Ценность здесь не в проверке отдельных функций — их разбирают тесты
 * бэкенда (2.1-2.6) и vitest на фронтенде (2.4) — а в стыках, которых не видит ни один из
 * них: доехал ли токен из письма до формы, тот ли slug в адресе, отправляет ли dnd-kit тот
 * самый PATCH, который ждёт бэкенд, обновился ли react-query после мутации.
 *
 * Один тест, а не семь: шаги не независимы (списывать время некуда, пока нет задачи), и
 * разбиение на отдельные тесты означало бы либо повторять предыдущие шаги в каждом, либо
 * тащить состояние между ними. test.step даёт ту же читаемость отчёта без этой цены.
 */

// Уникальный суффикс на прогон: MailHog и dev-база общие и не чистятся между запусками,
// а адрес и имя проекта должны быть свободными (email уникален в users, имя проекта — в
// пределах владельца, см. ProjectNameAlreadyExistsException).
const RUN_ID = Date.now()
const USER = {
  email: `e2e-${RUN_ID}@example.com`,
  // Никнейм уникален глобально (V28), а база между прогонами не чистится — поэтому,
  // как и адрес, он завязан на время запуска.
  username: `e2e-golden-${RUN_ID}`.slice(0, 30),
  password: 'e2e-password-123',
  lastName: 'Playwright',
  firstName: 'Golden',
}
const PROJECT_NAME = `E2E golden path ${RUN_ID}`
// Ни один статус колонки не входит в название задачи: колонки на доске отыскиваются по
// заголовку, и карточка с «New» в тексте сделала бы поиск неоднозначным.
const TASK_TITLE = `Ship the golden path ${RUN_ID}`
const HOURS_LOGGED = '2.5'

/**
 * Колонка канбана целиком — по её заголовку. Заголовок это единственное место на доске, где
 * встречается название статуса (карточки статус не показывают), поэтому точного совпадения
 * текста достаточно, чтобы не спутать колонки между собой.
 */
function column(page, statusLabel) {
  return page.locator('.min-w-64').filter({ has: page.getByText(statusLabel, { exact: true }) })
}

/**
 * Перетаскивание карточки. Через мышь по шагам, а не через dragTo(): dnd-kit слушает
 * pointer-события, а не HTML5 drag-and-drop, и «телепортация» курсора в цель ему не
 * подходит — PointerSensor настроен с activationConstraint.distance = 8 (см.
 * ProjectTasksPage), то есть до этого порога движение считается кликом, а не перетаскиванием,
 * и без промежуточных шагов drag просто не начнётся.
 */
async function dragCardInto(page, card, targetColumn) {
  const from = await card.boundingBox()
  const to = await targetColumn.boundingBox()

  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2)
  await page.mouse.down()
  // Первый сдвиг — чтобы преодолеть порог активации и запустить drag.
  await page.mouse.move(from.x + from.width / 2 + 20, from.y + from.height / 2, { steps: 5 })
  // Целимся под заголовок колонки: это область-droppable, а не сам заголовок.
  await page.mouse.move(to.x + to.width / 2, to.y + 100, { steps: 20 })
  await page.mouse.up()
}

test('золотой путь: регистрация → проект → задача → канбан → списание времени', async ({ page }) => {
  await test.step('регистрация и подтверждение почты', async () => {
    await page.goto('/register')

    await page.getByLabel('Email').fill(USER.email)
    await page.getByLabel('Username').fill(USER.username)
    await page.getByLabel('Password').fill(USER.password)
    await page.getByLabel('Last name').fill(USER.lastName)
    await page.getByLabel('First name').fill(USER.firstName)
    await page.getByRole('button', { name: 'Sign up' }).click()

    // Форма сменилась экраном «проверьте почту» — значит бэкенд ответил 201, а не ошибкой.
    await expect(page.getByText('Check your email')).toBeVisible()

    await page.goto(await awaitVerificationLink(USER.email))
    await expect(page.getByText('Email confirmed, you can sign in now.')).toBeVisible()
  })

  await test.step('вход', async () => {
    await page.getByRole('link', { name: 'Go to sign in' }).click()

    await page.getByLabel('Email').fill(USER.email)
    await page.getByLabel('Password').fill(USER.password)
    await page.getByRole('button', { name: 'Sign in' }).click()

    await expect(page).toHaveURL(/\/projects$/)
    await expect(page.getByRole('heading', { name: 'My projects' })).toBeVisible()
  })

  await test.step('создание проекта', async () => {
    // У нового аккаунта проектов нет, и кнопка «New project» на странице сразу вторая:
    // одна в шапке, вторая в пустом состоянии. Дожидаемся именно пустого состояния (то есть
    // загруженного списка) и жмём ту, что в нём, — её и нажал бы новый пользователь.
    // Без ожидания клик попадал бы то в один, то в другой момент отрисовки: пока список
    // грузится, ссылка одна, после — две, и Playwright в strict mode отказывается выбирать.
    await expect(page.getByText("You don't have any projects yet")).toBeVisible()
    await page.getByRole('link', { name: 'New project' }).last().click()

    await page.getByLabel('Name').fill(PROJECT_NAME)
    await page.getByRole('button', { name: 'Create' }).click()

    // Проект открылся по своему slug — slug генерируется на бэкенде, и то, что фронтенд
    // попал на существующий адрес, само по себе часть проверки.
    await expect(page).toHaveURL(/\/projects\/e2e-golden-path-\d+$/)
  })

  await test.step('создание задачи на доске', async () => {
    await page.getByRole('link', { name: 'Kanban' }).click()
    await expect(page).toHaveURL(/\/board$/)

    await page.getByLabel('New task').fill(TASK_TITLE)
    await page.getByRole('button', { name: 'Add', exact: true }).click()

    // Новая задача заводится в статусе NEW — колонка «New», а не какая-нибудь ещё.
    await expect(column(page, 'New').getByText(TASK_TITLE)).toBeVisible()
  })

  await test.step('перетаскивание карточки в другую колонку', async () => {
    const card = column(page, 'New').getByText(TASK_TITLE)
    await dragCardInto(page, card, column(page, 'In progress'))

    await expect(column(page, 'In progress').getByText(TASK_TITLE)).toBeVisible()
    await expect(column(page, 'New').getByText(TASK_TITLE)).toHaveCount(0)

    // Перезагрузка — главное в этом шаге. Без неё тест доказывал бы только то, что карточка
    // переехала в стейте react-query; вопрос же в том, доехал ли PATCH до базы.
    await page.reload()
    await expect(column(page, 'In progress').getByText(TASK_TITLE)).toBeVisible()
  })

  await test.step('списание времени', async () => {
    await column(page, 'In progress').getByText(TASK_TITLE).click()
    await expect(page).toHaveURL(/\/tasks\/\d+$/)
    await expect(page.getByRole('heading', { name: TASK_TITLE })).toBeVisible()

    // Дата уже подставлена сегодняшним числом — трогаем только часы.
    await page.getByLabel('Hours', { exact: true }).fill(HOURS_LOGGED)
    await page.getByRole('button', { name: 'Log time' }).click()

    await expect(page.getByText('Total: 2.50h')).toBeVisible()
  })

  await test.step('списанное время видно на доске', async () => {
    await page.getByRole('link', { name: 'Kanban' }).click()

    // Итог всей цепочки: задача в той колонке, куда её перетащили, и с теми часами, которые
    // ей списали, — прочитанные заново с бэкенда, а не оставшиеся в памяти вкладки.
    await expect(column(page, 'In progress').getByText(TASK_TITLE)).toBeVisible()
    await expect(column(page, 'In progress').getByText('2.50')).toBeVisible()
  })
})
