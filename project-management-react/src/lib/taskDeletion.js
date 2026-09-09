// Из чего складывается фраза «вместе с 3 подзадачами и 5 комментариями» (5.2). Порядок
// частей фиксирован и идёт от самого весомого к самому мелкому: подзадача — это отдельная
// задача, которая уедет в корзину целиком, а запись времени — строчка в таблице.
const PARTS = [
  ['subtasks', 'tasks.detail.deleteCountSubtasks'],
  ['comments', 'tasks.detail.deleteCountComments'],
  ['attachments', 'tasks.detail.deleteCountAttachments'],
  ['timeLogs', 'tasks.detail.deleteCountTimeLogs'],
]

/**
 * Текст под заголовком «Удалить задачу?»: что именно уедет в корзину и на сколько.
 *
 * Пустые составляющие не перечисляются: «0 вложений» — это не сведения, а шум, из-за
 * которого настоящие числа тонут. Если не набралось ни одной, остаётся общая фраза про
 * корзину — она верна и сама по себе, а не является заглушкой.
 *
 * summary === null означает, что счётчики получить не удалось (см. вызов в TaskEditPage);
 * поведение то же, что и у задачи без содержимого, и это сознательно: единственная
 * альтернатива — не задать вопрос вовсе.
 */
export function describeTaskDeletion(summary, t, locale) {
  const details = summary
    ? PARTS.filter(([key]) => summary[key] > 0).map(([key, i18nKey]) => t(i18nKey, { count: summary[key] }))
    : []

  if (details.length === 0) {
    return t('tasks.detail.deleteConfirmBody')
  }
  // Intl.ListFormat, а не join(', '): правила перечисления у языков разные (перед немецким
  // und запятая не ставится, перед английским and в коротком списке — тоже), и держать их в
  // голове не нужно — браузер знает сам.
  const list = new Intl.ListFormat(locale, { style: 'long', type: 'conjunction' }).format(details)
  return t('tasks.detail.deleteConfirmBodyWith', { details: list })
}
