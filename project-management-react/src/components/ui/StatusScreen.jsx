/**
 * Экран вместо содержимого: «не найдено», «нет доступа» и им подобные (5.3).
 *
 * Отдельный компонент, а не строчка красного текста на каждой странице, потому что все эти
 * состояния отвечают на один и тот же вопрос — «я пришёл по ссылке, а тут ничего нет, что
 * теперь?» — и ответ у них устроен одинаково: что случилось, почему и куда идти дальше.
 * Последнее — самое важное: тупик без выхода и есть та самая «размытость», из-за которой
 * опечатка в адресе выглядела как пропавшая задача.
 */
export function StatusScreen({ icon: Icon, title, description, hint, children }) {
  return (
    <div className="mx-auto flex max-w-md flex-col items-center gap-3 px-4 py-16 text-center">
      {Icon && <Icon className="size-10 text-gray-400 dark:text-gray-500" aria-hidden="true" />}
      <h1 className="text-xl font-semibold text-gray-900 dark:text-gray-100">{title}</h1>
      <p className="text-sm text-gray-600 dark:text-gray-400">{description}</p>
      {hint && (
        <p className="max-w-full truncate rounded bg-gray-100 px-2 py-1 font-mono text-xs text-gray-500 dark:bg-gray-800 dark:text-gray-400">
          {hint}
        </p>
      )}
      {children && <div className="mt-2 flex flex-wrap justify-center gap-2">{children}</div>}
    </div>
  )
}
