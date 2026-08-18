import { Check, ChevronDown, X } from 'lucide-react'
import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { inputClass } from './FormKit'

// Поле со свободным вводом + выпадающий список подсказок (combobox, а не select):
// значением может быть как выбранный из списка вариант, так и произвольный текст.
// Компонент "глупый" — не знает, откуда взялись options (см. Pagination): вызывающий
// код передаёт готовый список строк и получает строку обратно.
//
// Поведение раскрытия намеренно такое: по клику/фокусу показываем ВСЕ варианты
// (даже если поле уже заполнено), и только начатый ввод сужает список — поэтому
// фильтр хранится отдельным состоянием со значением null = "пользователь ещё не печатал".
export function Combobox({
  value,
  onChange,
  options,
  placeholder,
  maxLength,
  emptyText,
  noMatchesText,
  clearLabel,
  toggleLabel,
}) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [filter, setFilter] = useState(null)
  const [highlighted, setHighlighted] = useState(-1)

  const containerRef = useRef(null)
  const inputRef = useRef(null)
  const listRef = useRef(null)
  const listboxId = useId()
  const optionId = (index) => `${listboxId}-option-${index}`

  const items = useMemo(() => {
    const all = options ?? []
    if (filter === null) {
      return all
    }
    const query = filter.trim().toLowerCase()
    return query ? all.filter((option) => option.toLowerCase().includes(query)) : all
  }, [options, filter])

  // Закрытие по клику вне компонента — именно на mousedown, а не на blur инпута:
  // blur срабатывает раньше click по варианту, и выбор мышью не доходил бы до onChange.
  useEffect(() => {
    if (!open) {
      return undefined
    }
    function onMouseDown(event) {
      if (!containerRef.current?.contains(event.target)) {
        close()
      }
    }
    document.addEventListener('mousedown', onMouseDown)
    return () => document.removeEventListener('mousedown', onMouseDown)
  }, [open])

  // Подсвеченный клавиатурой вариант держим в зоне видимости — список скроллится
  // (max-h-60), и без этого стрелки уводили бы выделение за пределы окна.
  useEffect(() => {
    if (!open || highlighted < 0) {
      return
    }
    listRef.current
      ?.querySelector(`#${CSS.escape(`${listboxId}-option-${highlighted}`)}`)
      ?.scrollIntoView({ block: 'nearest' })
  }, [open, highlighted, listboxId])

  function close() {
    setOpen(false)
    setFilter(null)
    setHighlighted(-1)
  }

  function openAll() {
    setOpen(true)
    setFilter(null)
    setHighlighted(-1)
  }

  function select(option) {
    onChange(option)
    close()
    inputRef.current?.focus()
  }

  function onInputChange(event) {
    const next = event.target.value
    onChange(next)
    setFilter(next)
    setOpen(true)
    setHighlighted(-1)
  }

  function onKeyDown(event) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      if (!open) {
        openAll()
        return
      }
      if (items.length === 0) {
        return
      }
      const step = event.key === 'ArrowDown' ? 1 : -1
      setHighlighted((prev) => {
        const next = prev + step
        if (next < 0) return items.length - 1
        if (next >= items.length) return 0
        return next
      })
      return
    }
    if (event.key === 'Enter' && open && highlighted >= 0) {
      // preventDefault — иначе Enter по подсказке заодно сабмитил бы форму задачи.
      event.preventDefault()
      select(items[highlighted])
      return
    }
    if (event.key === 'Escape' && open) {
      event.preventDefault()
      close()
      return
    }
    if (event.key === 'Tab' && open) {
      close()
    }
  }

  const hasValue = Boolean(value)

  return (
    <div ref={containerRef} className="relative">
      <input
        ref={inputRef}
        type="text"
        role="combobox"
        autoComplete="off"
        aria-expanded={open}
        aria-controls={listboxId}
        aria-autocomplete="list"
        aria-activedescendant={open && highlighted >= 0 ? optionId(highlighted) : undefined}
        className={`${inputClass} pr-16`}
        placeholder={placeholder}
        maxLength={maxLength}
        value={value ?? ''}
        onChange={onInputChange}
        onMouseDown={() => {
          if (!open) openAll()
        }}
        onKeyDown={onKeyDown}
      />

      <div className="absolute inset-y-0 right-0 flex items-center gap-0.5 pr-2">
        {hasValue && (
          <button
            type="button"
            title={clearLabel ?? t('combobox.clear')}
            aria-label={clearLabel ?? t('combobox.clear')}
            className="rounded p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-600 dark:hover:text-gray-200"
            onClick={() => {
              onChange('')
              close()
              inputRef.current?.focus()
            }}
          >
            <X className="h-4 w-4" aria-hidden="true" />
          </button>
        )}
        <button
          type="button"
          tabIndex={-1}
          title={toggleLabel ?? t('combobox.toggle')}
          aria-label={toggleLabel ?? t('combobox.toggle')}
          className="rounded p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-600 dark:hover:text-gray-200"
          onClick={() => {
            if (open) {
              close()
            } else {
              openAll()
              inputRef.current?.focus()
            }
          }}
        >
          <ChevronDown
            className={`h-4 w-4 transition-transform ${open ? 'rotate-180' : ''}`}
            aria-hidden="true"
          />
        </button>
      </div>

      {open && (
        <ul
          ref={listRef}
          id={listboxId}
          role="listbox"
          className="absolute z-20 mt-1 max-h-60 w-full overflow-y-auto rounded-md border border-gray-200 bg-white py-1 shadow-lg dark:border-gray-600 dark:bg-gray-700"
        >
          {items.length === 0 && (
            <li className="px-3 py-2 text-sm text-gray-400 dark:text-gray-500">
              {(options ?? []).length === 0 ? emptyText : noMatchesText}
            </li>
          )}
          {items.map((option, index) => {
            const selected = option === value
            return (
              <li key={option} id={optionId(index)} role="option" aria-selected={selected}>
                {/* onMouseDown вместо onClick: инпут не успевает потерять фокус,
                    и выбор не конфликтует с обработчиком закрытия по mousedown вне. */}
                <button
                  type="button"
                  className={`flex w-full items-center gap-2 px-3 py-2 text-left text-sm ${
                    index === highlighted
                      ? 'bg-purple-50 text-purple-900 dark:bg-gray-600 dark:text-gray-100'
                      : 'text-gray-700 dark:text-gray-200'
                  }`}
                  onMouseDown={(event) => {
                    event.preventDefault()
                    select(option)
                  }}
                  onMouseEnter={() => setHighlighted(index)}
                >
                  <Check
                    className={`h-4 w-4 shrink-0 ${selected ? 'text-purple-600 dark:text-purple-400' : 'invisible'}`}
                    aria-hidden="true"
                  />
                  <span className="truncate">{option}</span>
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
