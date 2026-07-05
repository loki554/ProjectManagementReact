import ReactMarkdown from 'react-markdown'
import rehypeSanitize from 'rehype-sanitize'
import remarkGfm from 'remark-gfm'

// rehype-sanitize обязателен — описание задачи это произвольный MD от пользователей
// проекта, без санитайза это открытый XSS-вектор (см. §2 плана).
// Tailwind preflight обнуляет стили заголовков/списков/т.п., а плагин @tailwindcss/typography
// не подключён — восстанавливаем минимально необходимое через произвольные селекторы вместо
// того, чтобы тянуть отдельный плагин ради одного компонента.
const markdownClass =
  'text-sm text-gray-800 ' +
  '[&_h1]:mt-4 [&_h1]:mb-2 [&_h1]:text-xl [&_h1]:font-semibold [&_h1]:first:mt-0 ' +
  '[&_h2]:mt-4 [&_h2]:mb-2 [&_h2]:text-lg [&_h2]:font-semibold [&_h2]:first:mt-0 ' +
  '[&_h3]:mt-3 [&_h3]:mb-1 [&_h3]:text-base [&_h3]:font-semibold ' +
  '[&_p]:mb-2 [&_ul]:mb-2 [&_ul]:list-disc [&_ul]:pl-5 [&_ol]:mb-2 [&_ol]:list-decimal [&_ol]:pl-5 ' +
  '[&_li]:mb-0.5 [&_a]:text-purple-600 [&_a]:underline [&_strong]:font-semibold ' +
  '[&_code]:rounded [&_code]:bg-gray-100 [&_code]:px-1 [&_code]:py-0.5 [&_code]:text-xs ' +
  '[&_pre]:mb-2 [&_pre]:overflow-x-auto [&_pre]:rounded [&_pre]:bg-gray-100 [&_pre]:p-3 ' +
  '[&_blockquote]:border-l-2 [&_blockquote]:border-gray-300 [&_blockquote]:pl-3 [&_blockquote]:text-gray-600 ' +
  '[&_table]:mb-2 [&_table]:border-collapse [&_th]:border [&_th]:border-gray-300 [&_th]:bg-gray-50 [&_th]:px-2 [&_th]:py-1 [&_th]:text-left ' +
  '[&_td]:border [&_td]:border-gray-300 [&_td]:px-2 [&_td]:py-1'

export function MarkdownRenderer({ children }) {
  if (!children) {
    return null
  }
  return (
    <div className={markdownClass}>
      <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeSanitize]}>
        {children}
      </ReactMarkdown>
    </div>
  )
}
