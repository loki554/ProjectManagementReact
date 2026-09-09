import { afterEach, describe, expect, it } from 'vitest'
import i18n, { changeLanguage, loadLanguage } from './index'

// Тесты идут на английском (см. test/setup.js) — возвращаем язык, чтобы соседние файлы
// и следующие проверки в этом не зависели от порядка запуска.
afterEach(async () => {
  await changeLanguage('en')
})

/**
 * Словари грузятся по одному (5.4). Проверяется именно это: до первого обращения чужого
 * словаря в памяти нет, а переключение языка не показывает ключи вместо слов — то есть
 * словарь приезжает до, а не после `changeLanguage`.
 */
describe('загрузка словарей по требованию', () => {
  it('чужого словаря изначально нет — он не уезжает в основной файл', () => {
    expect(i18n.hasResourceBundle('de', 'translation')).toBe(false)
  })

  it('переключение языка сразу говорит на нём, а не на ключах', async () => {
    await changeLanguage('de')

    expect(i18n.language).toBe('de')
    expect(i18n.t('confirm.cancel')).toBe('Abbrechen')
  })

  it('повторная загрузка ничего не перезапрашивает', async () => {
    await loadLanguage('ru')
    i18n.addResourceBundle('ru', 'translation', { confirm: { cancel: 'подменено' } }, true, true)

    await loadLanguage('ru')

    // Словарь остался тем, что уже в памяти: loadLanguage не тронул готовый бандл.
    expect(i18n.getResource('ru', 'translation', 'confirm.cancel')).toBe('подменено')
  })

  it('неизвестный язык откатывается на английский, а не остаётся без словаря', async () => {
    expect(await loadLanguage('fr')).toBe('en')
    expect(i18n.hasResourceBundle('en', 'translation')).toBe(true)
  })
})
