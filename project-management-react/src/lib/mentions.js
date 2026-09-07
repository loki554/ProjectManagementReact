// @Упоминания в комментариях (4.5): разбор текста на стороне отображения и подстановка
// упоминания в текст при выборе из подсказок.
//
// Хранится и передаётся упоминание как `@почта` — это единственная форма, которую сервер
// может разобрать сам (см. MentionParser.java: список получателей уведомления — вопрос
// прав, и принимать его от клиента отдельным полем нельзя). Читать `@ivanov@example.com`
// человеку неудобно, поэтому адрес — это то, что хранится, а не то, что видно: здесь текст
// режется на куски, и упоминание известного участника рисуется его именем.
//
// Регулярное выражение повторяет серверное с одной поправкой: вместо ретроспективного
// просмотра (?<!…) захватывается сам предшествующий символ. Lookbehind в JS есть давно, но
// он ничего не даёт, а группа с предыдущим символом всё равно нужна — её текст возвращается
// в поток как обычный.
const MENTION_PATTERN = /(^|[^\w.%+-])@([\w.%+-]+@[\w.-]+\.[A-Za-z]{2,})/g

/**
 * Режет тело комментария на куски: { type: 'text', value } и { type: 'mention', email }.
 * Адреса приводятся к нижнему регистру — как их нормализует бэкенд перед сравнением с
 * участниками проекта, иначе @Ivanov@Example.com рисовался бы «неизвестным» упоминанием.
 */
export function splitMentions(body) {
  if (!body) {
    return []
  }
  const parts = []
  let lastIndex = 0
  let match
  MENTION_PATTERN.lastIndex = 0
  while ((match = MENTION_PATTERN.exec(body)) !== null) {
    const [, prefix, email] = match
    const textEnd = match.index + prefix.length
    if (textEnd > lastIndex) {
      parts.push({ type: 'text', value: body.slice(lastIndex, textEnd) })
    }
    parts.push({ type: 'mention', email: email.toLowerCase() })
    lastIndex = MENTION_PATTERN.lastIndex
  }
  if (lastIndex < body.length) {
    parts.push({ type: 'text', value: body.slice(lastIndex) })
  }
  return parts
}

/**
 * Незакрытое упоминание перед кареткой: `@` + то, что человек успел набрать, без пробелов.
 * Возвращает { query, start } либо null, если каретка сейчас не внутри упоминания.
 *
 * Строчка «стоп-символов» короче, чем шаблон адреса: пока человек печатает, набранное ещё
 * не адрес, и требовать от него валидности значило бы показывать подсказки только тому,
 * кто и так знает, что набирает. Поэтому упоминание тянется до первого пробела или
 * перевода строки — и обрывается, если перед `@` стоит буква (там это часть слова или
 * обычный адрес в тексте, а не обращение).
 */
export function findActiveMention(text, caret) {
  const upToCaret = text.slice(0, caret)
  const at = upToCaret.lastIndexOf('@')
  if (at === -1) {
    return null
  }
  const query = upToCaret.slice(at + 1)
  if (/[\s@]/.test(query)) {
    return null
  }
  const before = at > 0 ? upToCaret[at - 1] : ''
  if (before && /[\w.%+-]/.test(before)) {
    return null
  }
  return { query, start: at }
}

/**
 * Подставляет выбранный адрес вместо набранного `@…` и возвращает новый текст вместе с
 * позицией каретки.
 *
 * Пробел после адреса дописывается только там, где он нужен: перед следующей буквой (без
 * него слово приклеилось бы к упоминанию, и оно перестало бы им быть) и в конце строки,
 * где человек продолжает печатать. Перед запятой, точкой или уже стоящим пробелом его
 * добавлять не надо — упоминание и так на них кончается, а лишний пробел пришлось бы
 * стирать руками ровно в самом частом случае: «@Иванов, посмотри».
 */
export function applyMention(text, mention, email) {
  const before = text.slice(0, mention.start)
  const after = text.slice(mention.start + 1 + mention.query.length)
  const inserted = `@${email}` + (after === '' || /^\w/.test(after) ? ' ' : '')
  return {
    text: before + inserted + after,
    caret: mention.start + inserted.length,
  }
}

/** Фамилия Имя, как человека подписывают везде в интерфейсе. */
export function memberName(member) {
  return `${member.lastName} ${member.firstName}`.trim()
}

/**
 * Отбор участников по набранному после `@`. Ищем и по имени, и по адресу: имя человек
 * помнит, адрес — обычно нет, но подсказку по началу адреса ждут все, кто пользовался
 * почтой. Пустой запрос (только что набрали `@`) показывает всех — тот же приём, что в
 * Combobox: сузить список печатью человек может, а угадать, что там было, — нет.
 */
export function filterMembers(members, query) {
  const normalized = query.trim().toLowerCase()
  if (!normalized) {
    return members
  }
  return members.filter(
    (member) =>
      memberName(member).toLowerCase().includes(normalized) ||
      member.email.toLowerCase().includes(normalized),
  )
}
