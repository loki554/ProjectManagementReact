import { expect } from '@playwright/test'
import { E2E } from '../playwright.config.js'

/**
 * Чтение письма из MailHog. Регистрация без него не заканчивается: подтверждение почты —
 * обязательный шаг перед первым входом (см. AuthService.login и EmailNotVerifiedException),
 * и обойти его, дописав что-нибудь в базу, значило бы вырезать из «золотого пути» ровно ту
 * часть, где чаще всего и ломается.
 *
 * MailHog общий на машину и письма в нём копятся между прогонами — поэтому ищем строго по
 * адресу получателя, который у каждого прогона свой.
 */
async function search(email) {
  const url = `${E2E.mailhogUrl}/api/v2/search?kind=to&query=${encodeURIComponent(email)}&limit=10`
  const response = await fetch(url)
  if (!response.ok) {
    throw new Error(`MailHog ответил ${response.status} на ${url}`)
  }
  return (await response.json()).items ?? []
}

/**
 * Тело письма как текст. SimpleMailMessage отправляет русский текст, поэтому SMTP кодирует
 * его — обычно quoted-printable, иногда base64; ссылка при этом может оказаться разрезанной
 * мягким переносом (=\r\n) прямо посередине токена. Без декодирования регулярка ниже
 * находила бы её через раз — то есть тест падал бы «иногда», что хуже, чем не падать вовсе.
 */
function decodeBody(message) {
  const headers = message.Content?.Headers ?? {}
  const encoding = (headers['Content-Transfer-Encoding']?.[0] ?? '').toLowerCase()
  const body = message.Content?.Body ?? ''

  if (encoding === 'base64') {
    return Buffer.from(body, 'base64').toString('utf8')
  }
  if (encoding === 'quoted-printable') {
    return body
      .replace(/=\r?\n/g, '') // мягкий перенос строки — просто склейка
      .replace(/=([0-9A-F]{2})/gi, (_, hex) => String.fromCharCode(parseInt(hex, 16)))
  }
  return body
}

/**
 * Ждёт письмо с подтверждением и возвращает путь ссылки из него (`/verify-email?token=...`).
 * Именно путь, а не полный URL: письмо строится из app.frontend.base-url бэкенда, а тест
 * ходит на свой baseURL, и на разных портах это разные адреса при одном и том же токене.
 */
export async function awaitVerificationLink(email) {
  let link = null

  await expect
    .poll(
      async () => {
        for (const message of await search(email)) {
          const match = decodeBody(message).match(/\/verify-email\?token=[0-9a-fA-F-]{36}/)
          if (match) {
            link = match[0]
            return true
          }
        }
        return false
      },
      {
        message: `Письмо с подтверждением для ${email} так и не дошло до MailHog`,
        // Письмо уходит асинхронно, уже после ответа на /register (см. MailDispatcher).
        timeout: 20_000,
      },
    )
    .toBe(true)

  return link
}
