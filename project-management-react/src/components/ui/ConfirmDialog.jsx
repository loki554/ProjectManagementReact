import { useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { resolveConfirmation, useConfirmStore } from '../../stores/confirmStore'
import { primaryButtonClass, secondaryButtonClass } from './FormKit'

const dangerButtonClass =
  'rounded-md bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:cursor-not-allowed disabled:opacity-60'

const TONE_CLASSES = {
  danger: dangerButtonClass,
  primary: primaryButtonClass,
}

/**
 * Единственный диалог подтверждения на всё приложение (5.2). Смонтирован один раз в
 * App.jsx рядом с ToastContainer и рисует то, о чём его просит `confirmStore`.
 *
 * Нативный `<dialog>` с `showModal()`, а не собственный оверлей на div'ах: фокус-трап,
 * возврат фокуса на место, Esc, инертный фон и корректный порядок наложения — всё это
 * браузер делает сам, причём правильнее, чем самодельная реализация (там, где своя
 * ловушка фокуса забудет про адресную строку и содержимое iframe, нативная не забудет).
 * Цена — jsdom, который `showModal` не реализует вовсе; в тестах он подменяется заглушкой
 * (см. test/setup.js), а настоящие Esc и трап проверяются e2e в браузере.
 */
export function ConfirmDialogHost() {
  const { t } = useTranslation()
  const request = useConfirmStore((state) => state.request)
  const dialogRef = useRef(null)

  useEffect(() => {
    const dialog = dialogRef.current
    // Повторный showModal на уже открытом диалоге — исключение, а не no-op. Открытым он
    // здесь оказаться может: вопрос вправе смениться, пока диалог на экране.
    if (dialog && !dialog.open) {
      dialog.showModal()
    }
  }, [request])

  if (!request) {
    return null
  }

  return (
    <dialog
      ref={dialogRef}
      aria-labelledby="confirm-dialog-title"
      // Esc браузер обрабатывает сам и закрыл бы диалог мимо стора — тогда промис вызова
      // остался бы неразрешённым. Поэтому отменяем закрытие и отвечаем «нет» явно.
      onCancel={(event) => {
        event.preventDefault()
        resolveConfirmation(null)
      }}
      // Клик мимо окна (по подложке) целится в сам <dialog> — для вопроса это отказ, то
      // есть самый безопасный из возможных ответов.
      onClick={(event) => {
        if (event.target === dialogRef.current) {
          resolveConfirmation(null)
        }
      }}
      className="m-auto w-full max-w-md rounded-lg border border-gray-200 bg-white p-6 text-gray-900 shadow-xl backdrop:bg-black/40 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100"
    >
      <h2 id="confirm-dialog-title" className="text-base font-semibold">
        {request.title}
      </h2>
      {request.body && <p className="mt-2 text-sm text-gray-600 dark:text-gray-400">{request.body}</p>}

      {/* Отмена — первой и под фокусом: диалог всплывает поверх работы, и первое, что
          человек нажимает не глядя (Enter, пробел), не должно ничего удалять. */}
      <div className="mt-5 flex flex-wrap justify-end gap-2">
        <button
          type="button"
          autoFocus
          onClick={() => resolveConfirmation(null)}
          className={secondaryButtonClass}
        >
          {request.cancelLabel ?? t('confirm.cancel')}
        </button>
        {request.choices.map((choice) => (
          <button
            key={choice.id}
            type="button"
            onClick={() => resolveConfirmation(choice.id)}
            className={TONE_CLASSES[choice.tone] ?? TONE_CLASSES.danger}
          >
            {choice.label}
          </button>
        ))}
      </div>
    </dialog>
  )
}
