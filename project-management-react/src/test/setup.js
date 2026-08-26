import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'
import i18n from '../i18n'

// Язык в тестах фиксируем: i18next определяет его по localStorage/navigator, то есть на
// разных машинах интерфейс говорил бы на разных языках и ассерты по текстам разъезжались бы.
// Английский, а не русский, потому что он же fallbackLng — если ключа нет ни в одной локали,
// тест это увидит, а не подставит fallback молча.
await i18n.changeLanguage('en')

afterEach(() => {
  cleanup()
  localStorage.clear()
})
