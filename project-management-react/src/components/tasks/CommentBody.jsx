import { splitMentions } from '../../lib/mentions'

/**
 * Тело комментария с подсвеченными @упоминаниями (4.5).
 *
 * Хранится упоминание как `@почта` (единственная форма, которую разбирает сервер, — см.
 * MentionParser.java), а показывается именем: читать «@ivanov@example.com» посреди фразы
 * неудобно, а «@Иванов Иван» — обычное обращение.
 *
 * Адрес, которого нет среди участников проекта, остаётся текстом как есть и не
 * подсвечивается — ровно то же, что с ним сделал бы бэкенд: упоминание постороннего
 * никого не уведомляет, и делать вид, что кого-то позвали, нельзя.
 *
 * Своё упоминание выделено сильнее чужого: единственное, ради чего этот разбор и нужен, —
 * чтобы человек, пролистывая тред, увидел место, где обратились к нему.
 */
export function CommentBody({ body, membersByEmail, currentUserId }) {
  return (
    <p className="mt-0.5 text-sm whitespace-pre-wrap text-gray-700 dark:text-gray-300">
      {splitMentions(body).map((part, index) => {
        if (part.type === 'text') {
          return part.value
        }
        const member = membersByEmail?.get(part.email)
        if (!member) {
          return `@${part.email}`
        }
        const isMe = member.userId === currentUserId
        return (
          <span
            // Индекс как ключ — здесь он корректен: список кусков пересобирается целиком
            // из строки и порядок в нём ничего не переживает.
            key={index}
            title={member.email}
            className={`rounded px-1 font-medium ${
              isMe
                ? 'bg-purple-100 text-purple-800 dark:bg-purple-900/40 dark:text-purple-200'
                : 'text-purple-700 dark:text-purple-300'
            }`}
          >
            @{member.lastName} {member.firstName}
          </span>
        )
      })}
    </p>
  )
}
