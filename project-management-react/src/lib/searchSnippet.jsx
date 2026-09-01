import { Fragment } from 'react'

// Границы найденных слов внутри сниппета размечает бэкенд (SearchSnippet.java): SOH перед
// совпадением, STX после. Управляющие символы, а не готовые <mark>…</mark>, потому что
// подсветку делает ts_headline, а он не экранирует исходный текст — HTML оттуда пришлось бы
// вставлять через dangerouslySetInnerHTML прямо из описания задачи или тела комментария,
// то есть из данных пользователя. Здесь же строка режется на куски и рисуется обычными
// узлами React: подставить разметку через сниппет невозможно в принципе.
const START = '\u0001'
const END = '\u0002'

const MARK_CLASS = 'rounded bg-purple-100 px-0.5 text-purple-900 dark:bg-purple-900/60 dark:text-purple-100'

export function SearchSnippet({ text }) {
  if (!text) {
    return null
  }

  return (
    <>
      {text.split(START).map((chunk, index) => {
        // Кусок до первого маркера ничем не подсвечен — он всегда обычный текст.
        if (index === 0) {
          return <Fragment key={index}>{chunk}</Fragment>
        }
        const end = chunk.indexOf(END)
        // Непарный маркер (теоретически возможен, если ts_headline обрезал фрагмент ровно
        // по нему) — показываем остаток как обычный текст, а не теряем его.
        if (end < 0) {
          return <Fragment key={index}>{chunk}</Fragment>
        }
        return (
          <Fragment key={index}>
            <mark className={MARK_CLASS}>{chunk.slice(0, end)}</mark>
            {chunk.slice(end + 1)}
          </Fragment>
        )
      })}
    </>
  )
}
