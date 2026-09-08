import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
import { awaitVerificationLink } from './mailhog.js'

/**
 * Выгрузка данных проекта (4.12) через настоящий браузер.
 *
 * Здесь проверяется то, чего не видит ничто другое. Состав файлов, экранирование, права и
 * подзадачи покрывает `ExportIntegrationTest`, разбор Content-Disposition —
 * `lib/downloadBlob.test.js`. А вот что страница открывается из сайдбара; что кнопка
 * действительно отдаёт браузеру файл, а не молча падает где-то между apiClient и
 * `<a download>`; что имя файлу выбрал сервер; что внутри скачанного CSV лежит та самая
 * задача — и что кнопка вики включается ровно тогда, когда вики перестаёт быть пустой, —
 * это скачивание и навигация, то есть ровно то, чего в jsdom нет.
 */

const RUN_ID = Date.now()

const USER = {
  email: `e2e-export-${RUN_ID}@example.com`,
  username: `e2e-export-${RUN_ID}`.slice(0, 30),
  password: 'e2e-password-123',
  lastName: 'Playwright',
  firstName: 'Export',
}
const PROJECT_NAME = `E2E exporting ${RUN_ID}`
const TASK = 'Ship the export'
const SUBTASK = 'Write the CSV writer'
const WIKI_TEXT = '# Export handbook\n\nTake your data and run.'

test('задачи и вики уезжают файлами, имена которым выбрал сервер', async ({ page }) => {
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

  await test.step('проект, задача и подзадача под ней', async () => {
    await page.getByRole('link', { name: 'New project' }).last().click()
    await page.getByLabel('Name').fill(PROJECT_NAME)
    await page.getByRole('button', { name: 'Create' }).click()
    await expect(page).toHaveURL(/\/projects\/e2e-exporting-\d+$/)

    await page.getByRole('link', { name: 'Kanban' }).click()
    await page.getByLabel('New task').fill(TASK)
    await page.getByRole('button', { name: 'Add', exact: true }).click()
    await expect(page.getByText(TASK)).toBeVisible()

    // Подзадача — ради того, что на экранах не видно: в списке и на доске она спрятана
    // под родителем, а в файл обязана попасть отдельной строкой.
    await page.getByText(TASK).click()
    await expect(page).toHaveURL(/\/tasks\/\d+$/)
    await page.getByPlaceholder('Subtask title').fill(SUBTASK)
    await page.getByRole('button', { name: 'Add subtask' }).click()
    await expect(page.getByText(SUBTASK)).toBeVisible()
  })

  await test.step('страница выгрузки открывается из сайдбара', async () => {
    await page.getByRole('link', { name: 'Export', exact: true }).click()
    await expect(page).toHaveURL(/\/export$/)
    await expect(page.getByRole('heading', { name: 'Data export' })).toBeVisible()
  })

  /**
   * Файл требует авторизации, поэтому едет blob'ом через apiClient, а имя ему выбирает
   * фронтенд по Content-Disposition — заголовку, который до JS доезжает только потому, что
   * бэкенд явно выставил его в exposedHeaders (см. SecurityConfig). Проверить эту цепочку
   * целиком можно только настоящим скачиванием.
   */
  await test.step('CSV скачивается и содержит задачу вместе с подзадачей', async () => {
    const download = await downloadFrom(page, 'Tasks — CSV')

    expect(download.suggestedFilename()).toMatch(/^tasks-e2e-exporting-\d+-\d{4}-\d{2}-\d{2}\.csv$/)

    const csv = readFileSync(await download.path(), 'utf8')
    // BOM — ради Excel; читаем файл байтами, как его прочитает таблица.
    expect(csv.charCodeAt(0)).toBe(0xfeff)
    expect(csv).toContain(TASK)
    expect(csv).toContain(SUBTASK)
  })

  await test.step('JSON скачивается и разбирается как JSON', async () => {
    const download = await downloadFrom(page, 'Tasks — JSON')

    expect(download.suggestedFilename()).toMatch(/^tasks-e2e-exporting-\d+-\d{4}-\d{2}-\d{2}\.json$/)

    const payload = JSON.parse(readFileSync(await download.path(), 'utf8'))
    expect(payload.taskCount).toBe(2)
    expect(payload.tasks.map((task) => task.title)).toEqual(expect.arrayContaining([TASK, SUBTASK]))
  })

  // Пустую вики выгружать нечего, и файл на ноль байт — худший способ об этом сообщить.
  await test.step('кнопка вики включается только после первого сохранения', async () => {
    await expect(wikiRow(page).getByRole('button', { name: 'Download' })).toBeDisabled()

    await page.getByRole('link', { name: 'Wiki', exact: true }).click()
    await page.getByRole('button', { name: 'Edit' }).click()
    await page.getByPlaceholder('Project documentation in Markdown...').fill(WIKI_TEXT)
    await page.getByRole('button', { name: 'Save' }).click()
    await expect(page.getByRole('heading', { name: 'Export handbook' })).toBeVisible()

    await page.getByRole('link', { name: 'Export', exact: true }).click()
    await expect(wikiRow(page).getByRole('button', { name: 'Download' })).toBeEnabled()
  })

  await test.step('Markdown скачивается ровно тем текстом, что лежит в вики', async () => {
    const download = await downloadFrom(page, 'Wiki — Markdown')

    expect(download.suggestedFilename()).toMatch(/^wiki-e2e-exporting-\d+-\d{4}-\d{2}-\d{2}\.md$/)
    // Без BOM и без дописанного заголовка: файл должен открыться тем же текстом, который
    // человек видел в редакторе.
    expect(readFileSync(await download.path(), 'utf8')).toBe(WIKI_TEXT)
  })
})

// Строка списка выгрузок по её названию — три кнопки «Download» на странице отличаются
// только этим.
function wikiRow(page) {
  return page.getByRole('listitem').filter({ hasText: 'Wiki — Markdown' })
}

async function downloadFrom(page, rowTitle) {
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('listitem').filter({ hasText: rowTitle }).getByRole('button', { name: 'Download' }).click()
  return downloadPromise
}
