import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { inputClass, primaryButtonClass, secondaryButtonClass } from '../ui/FormKit'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { EMPTY_FILTERS, PRESET_VIEWS, filtersEqual, hasAnyFilter } from '../../lib/taskFilters'

/**
 * Панель представлений над списком задач (4.7): готовые наборы фильтров, свои сохранённые
 * и кнопка, превращающая то, что сейчас на экране, в новое представление.
 *
 * <p>Кнопки, а не выпадающий список. Представлений у человека единицы, и весь смысл пункта
 * в том, чтобы «мои просроченные» открывались одним нажатием, — список превратил бы это
 * нажатие в два и спрятал бы сам факт, что представления существуют.
 *
 * <p>Активное представление вычисляется сравнением фильтров, а не хранится в адресе. В URL
 * уезжают сами фильтры (см. taskFilters.writeFilters): представление персональное, и
 * ссылка вида ?view=&lt;uuid&gt; у того, кому её переслали, не открыла бы ничего. Побочный
 * эффект приятный: пришедшая ссылка подсвечивает то из своих представлений, которое с ней
 * совпало.
 */

const chipClass =
  'inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-sm whitespace-nowrap transition-colors'
const inactiveChipClass =
  'border-gray-300 bg-white text-gray-700 hover:bg-gray-100 dark:border-gray-600 dark:bg-gray-800 dark:text-gray-200 dark:hover:bg-gray-700'
const activeChipClass =
  'border-purple-500 bg-purple-100 font-medium text-purple-900 dark:border-purple-500 dark:bg-purple-950/60 dark:text-purple-200'

function Chip({ active, onClick, children, title }) {
  return (
    <button
      type="button"
      onClick={onClick}
      title={title}
      aria-pressed={active}
      className={`${chipClass} ${active ? activeChipClass : inactiveChipClass}`}
    >
      {children}
    </button>
  )
}

export function SavedViewsBar({
  filters,
  savedViews,
  appliedView,
  onApply,
  onSave,
  onUpdate,
  onDelete,
  onCopyLink,
  isSaving,
  error,
}) {
  const { t } = useTranslation()
  const [newName, setNewName] = useState(null)
  const nameInputRef = useRef(null)

  // Форма имени открывается по кнопке и сразу забирает фокус: иначе «сохранить» стоило бы
  // нажатия, попадания мышью в появившееся поле и только потом ввода.
  useEffect(() => {
    if (newName !== null) {
      nameInputRef.current?.focus()
    }
  }, [newName])

  const matchedView = savedViews?.find((view) => filtersEqual(filters, view.filters))
  // Представление, которое человек открыл и после этого поменял фильтры: его можно
  // перезаписать. Совпадающее по фильтрам (matchedView) перезаписывать нечем — оно и так
  // равно тому, что на экране.
  const modifiedView = appliedView && !filtersEqual(filters, appliedView.filters) ? appliedView : null

  function submitName(event) {
    event.preventDefault()
    const name = newName.trim()
    if (!name || isSaving) {
      return
    }
    onSave(name, () => setNewName(null))
  }

  return (
    <div className="flex flex-wrap items-center gap-2">
      <Chip active={!hasAnyFilter(filters)} onClick={() => onApply(EMPTY_FILTERS, null)}>
        {t('taskList.views.all')}
      </Chip>

      {PRESET_VIEWS.map((preset) => (
        <Chip
          key={preset.id}
          active={filtersEqual(filters, preset.filters)}
          onClick={() => onApply(preset.filters, null)}
        >
          {t(`taskList.views.presets.${preset.id}`)}
        </Chip>
      ))}

      {savedViews?.length > 0 && (
        <span aria-hidden="true" className="mx-1 h-5 w-px bg-gray-300 dark:bg-gray-600" />
      )}

      {savedViews?.map((view) => {
        const active = matchedView?.id === view.id
        return (
          // Своё представление — это чип с крестиком внутри, поэтому здесь не <Chip>:
          // кнопка внутри кнопки — невалидная разметка, и оформление приходится вешать на
          // обёртку, а не на саму кнопку.
          <span key={view.id} className={`${chipClass} ${active ? activeChipClass : inactiveChipClass}`}>
            <button
              type="button"
              onClick={() => onApply(view.filters, view.id)}
              aria-pressed={active}
              className="max-w-48 truncate"
            >
              {view.name}
            </button>
            {/* Крестик — только у активного представления: на всех сразу он превращает
                панель в ряд мишеней, в которые легко попасть, целясь в соседнюю кнопку.
                Подтверждения нет намеренно: удаляется закладка, а не данные, и собрать её
                заново — это те же фильтры плюс имя. */}
            {active && (
              <button
                type="button"
                onClick={() => onDelete(view)}
                aria-label={t('taskList.views.delete', { name: view.name })}
                className="-mr-1 px-1 text-purple-700 hover:text-red-600 dark:text-purple-300 dark:hover:text-red-400"
              >
                ×
              </button>
            )}
          </span>
        )
      })}

      <div className="ml-auto flex items-center gap-2">
        {error && (
          <span className="text-sm text-red-600 dark:text-red-400">
            {getLocalizedErrorMessage(error, t)}
          </span>
        )}

        {modifiedView && (
          <button type="button" onClick={() => onUpdate(modifiedView)} className={secondaryButtonClass}>
            {t('taskList.views.update', { name: modifiedView.name })}
          </button>
        )}

        {newName === null ? (
          <>
            <button
              type="button"
              onClick={onCopyLink}
              className={secondaryButtonClass}
              title={t('taskList.views.copyLinkHint')}
            >
              {t('taskList.views.copyLink')}
            </button>
            {/* Сохранять нечего, пока не выбран ни один фильтр: представление «список как
                он есть» — это кнопка «Все задачи», которая уже стоит слева. */}
            <button
              type="button"
              onClick={() => setNewName('')}
              disabled={!hasAnyFilter(filters) || Boolean(matchedView)}
              className={secondaryButtonClass}
            >
              {t('taskList.views.save')}
            </button>
          </>
        ) : (
          <form onSubmit={submitName} className="flex items-center gap-2">
            {/* Ширину задаёт обёртка, а не само поле: в inputClass уже есть w-full, и
                добавленный рядом w-52 с ним не спорит, а проигрывает. */}
            <span className="inline-block w-52">
              <input
                ref={nameInputRef}
                value={newName}
                onChange={(event) => setNewName(event.target.value)}
                onKeyDown={(event) => event.key === 'Escape' && setNewName(null)}
                maxLength={100}
                aria-label={t('taskList.views.nameLabel')}
                placeholder={t('taskList.views.namePlaceholder')}
                className={inputClass}
              />
            </span>
            <button type="submit" disabled={!newName.trim() || isSaving} className={primaryButtonClass}>
              {isSaving ? t('taskList.views.saving') : t('taskList.views.confirmSave')}
            </button>
            <button type="button" onClick={() => setNewName(null)} className={secondaryButtonClass}>
              {t('taskList.views.cancel')}
            </button>
          </form>
        )}
      </div>
    </div>
  )
}
