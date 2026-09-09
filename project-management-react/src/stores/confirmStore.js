import { create } from 'zustand'

/**
 * Подтверждение деструктивных действий (5.2). До этого везде стоял `window.confirm`: он
 * выглядит чужеродно, не переводится, не стилизуется, блокирует поток и умеет ровно два
 * ответа — «ок» и «отмена», из-за чего вопрос «куда девать незакрытые задачи спринта»
 * приходилось задавать так, что «отмена» означала не отмену, а второй вариант.
 *
 * Стор, а не контекст с провайдером: спрашивают из обработчиков событий и из `onError`
 * мутаций react-query, то есть оттуда, где хук уже не вызвать. Тот же приём и по той же
 * причине, что у тостов (`toastStore`).
 */
export const useConfirmStore = create(() => ({ request: null }))

// Резолвер живёт рядом со стором, а не внутри: это не состояние интерфейса, а обратная
// связь конкретного вызова. В сторе ему пришлось бы храниться функцией, которую никто не
// рисует и которую при перерисовке легко потерять.
let settle = null

/**
 * Вопрос с двумя ответами — общий случай. Возвращает промис с true/false, поэтому вызов на
 * месте читается так же, как читался `window.confirm`, только с `await`:
 *
 *     if (!(await confirmAction({ ... }))) return
 */
export function confirmAction({ title, body, confirmLabel, cancelLabel, tone = 'danger' }) {
  return askConfirmation({
    title,
    body,
    cancelLabel,
    choices: [{ id: 'confirm', label: confirmLabel, tone }],
  }).then((outcome) => outcome === 'confirm')
}

/**
 * Вопрос, у которого ответов больше двух (завершение спринта: «перенести в следующий», «в
 * бэклог», «не завершать»). Возвращает id выбранного варианта или null, если человек
 * отказался. Ради одного такого места отдельный компонент не нужен, а вот отдельная
 * функция — нужна: делать `confirmAction` трёхзначным ради него значило бы усложнить
 * пятнадцать вызовов ради шестнадцатого.
 */
export function askConfirmation({ title, body, cancelLabel, choices }) {
  return new Promise((resolve) => {
    // Двух вопросов одновременно быть не может. Предыдущий закрывается отказом, а не
    // молча забывается: иначе его промис никогда бы не разрешился и вызвавшая функция
    // осталась бы висеть на await навсегда.
    settle?.(null)
    settle = resolve
    useConfirmStore.setState({ request: { title, body, cancelLabel, choices } })
  })
}

/** Ответ на текущий вопрос: id варианта либо null (кнопка отмены, Esc, клик мимо диалога). */
export function resolveConfirmation(outcome) {
  const resolve = settle
  settle = null
  useConfirmStore.setState({ request: null })
  resolve?.(outcome)
}
