import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'
import { changeLanguage } from '../i18n'

// Язык в тестах фиксируем: i18next определяет его по localStorage/navigator, то есть на
// разных машинах интерфейс говорил бы на разных языках и ассерты по текстам разъезжались бы.
// Английский, а не русский, потому что он же fallbackLng — если ключа нет ни в одной локали,
// тест это увидит, а не подставит fallback молча.
await changeLanguage('en')

// jsdom (30.x) знает элемент <dialog>, но не реализует showModal/close: ни top layer, ни
// фокус-трапа у него нет в принципе. Подменяем на минимум — открыть и закрыть, — которого
// хватает, чтобы проверять содержимое и кнопки диалога подтверждения (5.2). Сами фокус-трап и
// Esc проверяются e2e в настоящем браузере — здесь их проверять было бы не на чём.
if (!HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal() {
    this.open = true
  }
  HTMLDialogElement.prototype.close = function close(returnValue) {
    this.open = false
    if (returnValue !== undefined) {
      this.returnValue = returnValue
    }
    this.dispatchEvent(new Event('close'))
  }
}

afterEach(() => {
  cleanup()
  localStorage.clear()
})
