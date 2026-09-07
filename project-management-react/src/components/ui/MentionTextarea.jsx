import { useEffect, useId, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { applyMention, filterMembers, findActiveMention, memberName } from '../../lib/mentions'
import { inputClass } from './FormKit'
import { UserAvatar } from './UserAvatar'

/**
 * Текстовое поле с автокомплитом @упоминаний по участникам проекта (4.5).
 *
 * Своё, а не `Combobox`: тот — поле, значение которого целиком равно одному выбранному
 * варианту, и вся его механика (кнопка «очистить», галочка у выбранного, раскрытие всего
 * списка по клику) построена вокруг этого. Здесь подсказка относится к куску текста
 * посередине, поле остаётся многострочным, а список появляется только пока набирается
 * `@…`. Общее у них — правила клавиатуры (стрелки, Enter, Escape, Tab) и выбор мышью на
 * `onMouseDown`, а не `onClick`, чтобы поле не успело потерять фокус; они здесь повторены
 * сознательно, потому что делить их пришлось бы через абстракцию заметно сложнее обеих.
 *
 * Список рисуется под полем, а не у каретки: попасть в позицию каретки в textarea можно
 * только зеркальным div'ом с копией всех стилей, и цена этой точности — целый механизм,
 * который ломается от любой правки вёрстки. Поле здесь узкое и короткое, список под ним
 * читается однозначно.
 */
export function MentionTextarea({
  value,
  onChange,
  members,
  rows = 3,
  placeholder,
  maxLength,
  autoFocus,
  className,
  textareaRef: externalRef,
}) {
  const { t } = useTranslation()
  const internalRef = useRef(null)
  const textareaRef = externalRef ?? internalRef
  const listRef = useRef(null)
  const listboxId = useId()
  const optionId = (index) => `${listboxId}-option-${index}`

  // null — подсказки закрыты. Иначе { query, start } из findActiveMention.
  const [mention, setMention] = useState(null)
  const [highlighted, setHighlighted] = useState(0)

  const matches = mention ? filterMembers(members ?? [], mention.query) : []
  const open = mention !== null && matches.length > 0

  // Список скроллится (max-h-56) — подсвеченный стрелками вариант держим в зоне видимости.
  useEffect(() => {
    if (!open) {
      return
    }
    listRef.current
      ?.querySelector(`#${CSS.escape(`${listboxId}-option-${highlighted}`)}`)
      ?.scrollIntoView({ block: 'nearest' })
  }, [open, highlighted, listboxId])

  function refresh(text, caret) {
    const next = findActiveMention(text, caret)
    // Дописанный до конца никнейм закрывает подсказки. Без этого комментарий, кончающийся
    // упоминанием (а это самый обычный случай — «посмотри, @ivanov»), оставлял бы открытый
    // список висеть поверх кнопки «Отправить», и клик по ней попадал бы в список. Стоит
    // продолжить печатать — запрос перестаёт совпадать точно, и подсказки возвращаются.
    const complete =
      next !== null && (members ?? []).some((member) => member.username === next.query.toLowerCase())
    setMention(complete ? null : next)
    setHighlighted(0)
  }

  function handleChange(event) {
    const { value: text, selectionStart } = event.target
    onChange(text)
    refresh(text, selectionStart)
  }

  // Каретку двигают не только буквы: стрелки, клик, Home/End. Пересчёт по событиям
  // выделения (а не только по вводу) — единственный способ закрыть подсказки, когда
  // человек ушёл курсором из `@…` в другое место строки.
  function handleSelect(event) {
    if (mention === null) {
      return
    }
    refresh(event.target.value, event.target.selectionStart)
  }

  function select(member) {
    const { text, caret } = applyMention(value ?? '', mention, member.username)
    onChange(text)
    setMention(null)
    const textarea = textareaRef.current
    if (textarea) {
      // Позицию каретки выставляем после того, как React отрисует новое значение —
      // иначе её тут же сбросит в конец обновление value.
      requestAnimationFrame(() => {
        textarea.focus()
        textarea.setSelectionRange(caret, caret)
      })
    }
  }

  function handleKeyDown(event) {
    if (!open) {
      return
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      const step = event.key === 'ArrowDown' ? 1 : -1
      setHighlighted((prev) => (prev + step + matches.length) % matches.length)
      return
    }
    if (event.key === 'Enter' || event.key === 'Tab') {
      // preventDefault и на Enter, и на Tab: первый иначе перевёл бы строку, второй увёл
      // бы фокус с поля — вместо того чтобы выбрать подсвеченного человека.
      event.preventDefault()
      select(matches[highlighted])
      return
    }
    if (event.key === 'Escape') {
      // Останавливаем всплытие: Escape внутри подсказок закрывает подсказки, а не форму
      // правки комментария вокруг них.
      event.preventDefault()
      event.stopPropagation()
      setMention(null)
    }
  }

  return (
    <div className="relative">
      <textarea
        ref={textareaRef}
        rows={rows}
        className={className ?? inputClass}
        placeholder={placeholder}
        maxLength={maxLength}
        autoFocus={autoFocus}
        value={value ?? ''}
        role="combobox"
        aria-expanded={open}
        aria-controls={open ? listboxId : undefined}
        aria-autocomplete="list"
        aria-activedescendant={open ? optionId(highlighted) : undefined}
        onChange={handleChange}
        onSelect={handleSelect}
        onKeyDown={handleKeyDown}
        onBlur={() => setMention(null)}
      />

      {open && (
        <ul
          ref={listRef}
          id={listboxId}
          role="listbox"
          aria-label={t('tasks.comments.mentionListLabel')}
          className="absolute z-20 mt-1 max-h-56 w-full overflow-y-auto rounded-md border border-gray-200 bg-white py-1 shadow-lg dark:border-gray-600 dark:bg-gray-700"
        >
          {matches.map((member, index) => (
            <li key={member.userId} id={optionId(index)} role="option" aria-selected={index === highlighted}>
              <button
                type="button"
                tabIndex={-1}
                className={`flex w-full items-center gap-2 px-3 py-2 text-left text-sm ${
                  index === highlighted
                    ? 'bg-purple-50 text-purple-900 dark:bg-gray-600 dark:text-gray-100'
                    : 'text-gray-700 dark:text-gray-200'
                }`}
                onMouseDown={(event) => {
                  event.preventDefault()
                  select(member)
                }}
                onMouseEnter={() => setHighlighted(index)}
              >
                {/* MemberResponse — плоский (userId вместо id), но UserAvatar берёт из
                    него только avatarUrl и инициалы, поэтому подходит как есть. */}
                <UserAvatar user={member} sizeClass="h-6 w-6" />
                <span className="min-w-0">
                  {/* Никнейм первой строкой, а имя второй: в текст подставится именно он,
                      и видеть человек должен то, что получит. */}
                  <span className="block truncate font-medium">@{member.username}</span>
                  <span className="block truncate text-xs text-gray-400 dark:text-gray-500">
                    {memberName(member)}
                  </span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
