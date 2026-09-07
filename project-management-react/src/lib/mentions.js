// @Упоминания в комментариях (4.5): разбор текста на стороне отображения и подстановка
// упоминания в текст при выборе из подсказок.
//
// Упоминание — это `@никнейм` (см. User.username, V28). Разбирать его обязан сервер (список
// получателей уведомления это права, см. MentionParser.java), поэтому в тексте стоит ровно
// то, что сервер опознаёт сам. Здесь та же грамматика, повторённая для показа: текст режется
// на куски, и никнейм известного участника рисуется чипом с настоящим именем в подсказке.
//
// Регулярное выражение повторяет серверное с одной поправкой: вместо ретроспективного
// просмотра (?<!…) захватывается сам предшествующий символ. Lookbehind в JS есть давно, но
// он ничего не даёт, а группа с предыдущим символом всё равно нужна — её текст возвращается
// в поток как обычный.
const MENTION_PATTERN = /(^|[^\w@-])@([\w-]{3,30})(?![\w-])/g

/** Формат никнейма для форм — тот же, что UsernameNormalizer.INPUT_PATTERN на бэкенде. */
export const USERNAME_PATTERN = /^[A-Za-z0-9_-]{3,30}$/
export const USERNAME_MAX_LENGTH = 30

/**
 * Режет тело комментария на куски: { type: 'text', value } и { type: 'mention', username }.
 * Никнеймы приводятся к нижнему регистру — как их нормализует бэкенд перед сравнением с
 * участниками проекта, иначе @Ivanov рисовался бы «неизвестным» упоминанием.
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
    const [, prefix, username] = match
    const textEnd = match.index + prefix.length
    if (textEnd > lastIndex) {
      parts.push({ type: 'text', value: body.slice(lastIndex, textEnd) })
    }
    parts.push({ type: 'mention', username: username.toLowerCase() })
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
 * Требований к набранному меньше, чем к готовому никнейму: пока человек печатает, «iv» ещё
 * не никнейм, и требовать трёх символов значило бы показывать подсказки только тому, кто и
 * так почти дописал. Поэтому упоминание тянется до первого пробела и обрывается, если перед
 * `@` стоит буква (там это часть слова или почтовый адрес в тексте, а не обращение).
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
  if (before && /[\w@-]/.test(before)) {
    return null
  }
  return { query, start: at }
}

/**
 * Подставляет выбранный никнейм вместо набранного `@…` и возвращает новый текст вместе с
 * позицией каретки.
 *
 * Пробел после никнейма дописывается только там, где он нужен: перед следующей буквой (без
 * него слово приклеилось бы к упоминанию, и оно перестало бы им быть) и в конце строки, где
 * человек продолжает печатать. Перед запятой, точкой или уже стоящим пробелом его добавлять
 * не надо — упоминание и так на них кончается, а лишний пробел пришлось бы стирать руками
 * ровно в самом частом случае: «@ivanov, посмотри».
 */
export function applyMention(text, mention, username) {
  const before = text.slice(0, mention.start)
  const after = text.slice(mention.start + 1 + mention.query.length)
  const inserted = `@${username}` + (after === '' || /^[\w-]/.test(after) ? ' ' : '')
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
 * Отбор участников по набранному после `@`. Ищем и по никнейму, и по имени: никнейм человек
 * набирает, если помнит, а если не помнит — набирает фамилию и находит его в подсказке.
 * Пустой запрос (только что набрали `@`) показывает всех — тот же приём, что в Combobox:
 * сузить список печатью человек может, а угадать, что там было, — нет.
 */
export function filterMembers(members, query) {
  const normalized = query.trim().toLowerCase()
  if (!normalized) {
    return members
  }
  return members.filter(
    (member) =>
      member.username.toLowerCase().includes(normalized) ||
      memberName(member).toLowerCase().includes(normalized),
  )
}
