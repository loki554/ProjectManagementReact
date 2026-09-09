import { describe, expect, it } from 'vitest'
import de from './locales/de.json'
import en from './locales/en.json'
import ru from './locales/ru.json'

/**
 * Словари грузятся по одному (5.4), и это убрало страховку, которая раньше была бесплатной:
 * пока в памяти лежали все три, забытый в ru ключ подменялся английским через fallbackLng.
 * Теперь у русского интерфейса английского словаря просто нет — на месте забытого ключа
 * человек увидит `tasks.detail.deleteConfirm`.
 *
 * Поэтому полнота проверяется здесь, до сборки, а не подпирается загрузкой второго словаря на
 * каждой странице. Заодно это тот самый тест, которого не хватало дисциплине «три локали
 * синхронно»: раньше её держали только на внимательности.
 */
function leafKeys(node, prefix = '') {
  return Object.entries(node).flatMap(([key, value]) =>
    value !== null && typeof value === 'object' ? leafKeys(value, `${prefix}${key}.`) : [`${prefix}${key}`],
  )
}

// Формы множественного числа у языков разные: в русском их четыре (one/few/many/other), в
// английском и немецком — две. Сравнивать имена ключей с суффиксами напрямую нельзя, поэтому
// суффикс отбрасывается, а множественность каждого ключа проверяется отдельно, ниже.
function pluralAgnosticKeys(node) {
  return new Set(leafKeys(node).map((key) => key.replace(/_(zero|one|two|few|many|other)$/, '')))
}

const LOCALES = { en, ru, de }

describe('локали', () => {
  it.each([['ru'], ['de']])('%s знает ровно то же, что и en', (language) => {
    const reference = pluralAgnosticKeys(en)
    const actual = pluralAgnosticKeys(LOCALES[language])

    expect([...actual].filter((key) => !reference.has(key)).sort()).toEqual([])
    expect([...reference].filter((key) => !actual.has(key)).sort()).toEqual([])
  })

  it('множественное число в русском описано всеми четырьмя формами', () => {
    // one/few/many/other: «1 задача», «2 задачи», «5 задач» и то же самое для дробных. Забытая
    // форма не ломается заметно — она молча превращается в «5 задача».
    const plurals = leafKeys(ru).filter((key) => /_(one|few|many|other)$/.test(key))
    const byBase = new Map()
    for (const key of plurals) {
      const base = key.replace(/_(one|few|many|other)$/, '')
      byBase.set(base, [...(byBase.get(base) ?? []), key.split('_').pop()])
    }

    const incomplete = [...byBase.entries()]
      .filter(([, forms]) => !['one', 'few', 'many', 'other'].every((form) => forms.includes(form)))
      .map(([base]) => base)

    expect(incomplete).toEqual([])
  })

  it('ни один перевод не пустой', () => {
    for (const [language, dictionary] of Object.entries(LOCALES)) {
      const empty = leafKeys(dictionary).filter((key) => {
        const value = key.split('.').reduce((node, part) => node[part], dictionary)
        return typeof value !== 'string' || value.trim() === ''
      })

      expect({ [language]: empty }).toEqual({ [language]: [] })
    }
  })
})
