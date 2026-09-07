import { splitMentions } from '../../lib/mentions'

/**
 * Тело комментария с подсвеченными @упоминаниями (4.5).
 *
 * Никнейм показывается как есть, а настоящее имя уходит в подсказку. Подставлять на его
 * место «@Иванов Иван», как делала версия с почтой, теперь незачем: адрес был нечитаем и
 * его приходилось прятать, а никнейм затем и придуман, чтобы его было видно. Заодно
 * написанное и показанное совпадают — человек, набравший `@ivanov`, видит `@ivanov`.
 *
 * Никнейм, которого нет среди участников проекта, остаётся обычным текстом и не
 * подсвечивается — ровно то же, что с ним сделал бы бэкенд: упоминание постороннего никого
 * не уведомляет, и делать вид, что кого-то позвали, нельзя. Сюда же попадают упоминания
 * тех, кто с тех пор сменил никнейм (см. MentionParser): текст читается по-прежнему,
 * подсветки нет.
 *
 * Своё упоминание выделено сильнее чужого: единственное, ради чего этот разбор и нужен, —
 * чтобы человек, пролистывая тред, увидел место, где обратились к нему.
 */
export function CommentBody({ body, membersByUsername, currentUserId }) {
  return (
    <p className="mt-0.5 text-sm whitespace-pre-wrap text-gray-700 dark:text-gray-300">
      {splitMentions(body).map((part, index) => {
        if (part.type === 'text') {
          return part.value
        }
        const member = membersByUsername?.get(part.username)
        if (!member) {
          return `@${part.username}`
        }
        const isMe = member.userId === currentUserId
        return (
          <span
            // Индекс как ключ — здесь он корректен: список кусков пересобирается целиком
            // из строки и порядок в нём ничего не переживает.
            key={index}
            title={`${member.lastName} ${member.firstName}`}
            className={`rounded px-1 font-medium ${
              isMe
                ? 'bg-purple-100 text-purple-800 dark:bg-purple-900/40 dark:text-purple-200'
                : 'text-purple-700 dark:text-purple-300'
            }`}
          >
            @{member.username}
          </span>
        )
      })}
    </p>
  )
}
