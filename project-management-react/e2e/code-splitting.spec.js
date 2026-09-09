import { expect, test } from '@playwright/test'

/**
 * Ленивая загрузка (5.4) с той стороны, с которой её видно только в браузере: что именно
 * страница скачивает и когда.
 *
 * Размер собранных файлов проверяется сборкой, а вот «на экране входа не грузится редактор
 * Markdown и словари чужих языков» — утверждение про исполнение, а не про сборку, и
 * ошибиться в нём легко: достаточно одного статического импорта где-нибудь по дороге, и
 * половина ленивого графа снова приедет вместе с первой страницей. Здесь это и проверяется —
 * по списку сетевых запросов.
 *
 * Регистрации и стенда сценарий не требует: всё происходит до входа. Пути в dev-режиме
 * ведут прямо на исходники (Vite отдаёт модули по одному), поэтому и модуль редактора, и
 * словарь видны в запросах под своими именами.
 */
test('на экране входа нет ни редактора Markdown, ни чужих словарей', async ({ page }) => {
  const requested = []
  page.on('request', (request) => requested.push(request.url()))

  await page.goto('/login')
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible()

  const asked = (pattern) => requested.some((url) => pattern.test(url))

  await test.step('тяжёлое не приезжает вместе с формой входа', async () => {
    // Редактор Markdown — самый большой кусок приложения, больше всего остального вместе
    // взятого. На форме с двумя полями ему делать нечего.
    expect(asked(/RichEditor|react-md-editor/)).toBe(false)
    // Как и канбану с его dnd-kit, и вообще любой странице проекта.
    expect(asked(/ProjectTasksPage|dnd-kit/)).toBe(false)
  })

  await test.step('словарь — только тот, на котором говорят', async () => {
    expect(asked(/locales\/en\.json/)).toBe(true)
    expect(asked(/locales\/ru\.json/)).toBe(false)
    expect(asked(/locales\/de\.json/)).toBe(false)
  })

  await test.step('чужой словарь приезжает в момент переключения, а не заранее', async () => {
    await page.getByRole('button', { name: 'Русский' }).click()

    await expect(page.getByRole('button', { name: 'Войти' })).toBeVisible()
    expect(asked(/locales\/ru\.json/)).toBe(true)
    // Немецкий так и не понадобился.
    expect(asked(/locales\/de\.json/)).toBe(false)
  })

  await test.step('выбор языка переживает перезагрузку — и снова без лишних словарей', async () => {
    requested.length = 0
    await page.reload()

    await expect(page.getByRole('button', { name: 'Войти' })).toBeVisible()
    expect(asked(/locales\/ru\.json/)).toBe(true)
    expect(asked(/locales\/en\.json/)).toBe(false)
  })
})
