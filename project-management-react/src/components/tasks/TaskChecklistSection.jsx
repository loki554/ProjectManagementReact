import { Check, Pencil, Trash2, X } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  useAddChecklistItem,
  useChecklist,
  useDeleteChecklistItem,
  useUpdateChecklistItem,
} from '../../api/checklistQueries'
import { inputClass, primaryButtonClass } from '../ui/FormKit'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'

/**
 * Чек-лист задачи (4.13): шаги внутри одной задачи.
 *
 * Отдельная секция, а не список подзадач: подзадача — это работа, которую кому-то поручают,
 * у неё есть номер, исполнитель, статус и место на доске. Пункт чек-листа — строка «сделано
 * или нет» в чужой работе, и всё, что с ним делают, — ставят и снимают галочку.
 *
 * Секция целиком скрыта, если чек-листа нет и завести его некому (VIEWER, архивный проект):
 * пустой заголовок «Чек-лист» без единой строки — это шум на странице, где и без него
 * хватает секций.
 */
export function TaskChecklistSection({ taskId, projectId, canManage }) {
  const { t } = useTranslation()
  const { data: items, isLoading, isError, error } = useChecklist(taskId)
  const addItem = useAddChecklistItem(projectId, taskId)
  const updateItem = useUpdateChecklistItem(projectId, taskId)
  const deleteItem = useDeleteChecklistItem(projectId, taskId)

  const [draft, setDraft] = useState('')
  // Правка текста пункта — на месте: пунктов немного, и открывать ради строки в 500
  // символов отдельную форму было бы дороже, чем сама правка.
  const [editingId, setEditingId] = useState(null)
  const [editingText, setEditingText] = useState('')

  if (!isLoading && !isError && (items?.length ?? 0) === 0 && !canManage) {
    return null
  }

  const done = items?.filter((item) => item.done).length ?? 0
  const total = items?.length ?? 0

  function onAdd(event) {
    event.preventDefault()
    const content = draft.trim()
    if (!content) {
      return
    }
    addItem.mutate(content, { onSuccess: () => setDraft('') })
  }

  function onSaveText(itemId) {
    const content = editingText.trim()
    if (!content) {
      return
    }
    updateItem.mutate({ itemId, patch: { content } }, { onSuccess: () => setEditingId(null) })
  }

  return (
    <div className="rounded-lg border border-gray-200 bg-white p-6 dark:border-gray-700 dark:bg-gray-800">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-gray-900 dark:text-gray-100">{t('checklist.title')}</h2>
        {total > 0 && (
          <span className="text-xs font-medium text-gray-500 dark:text-gray-400">
            {t('checklist.progress', { done, total })}
          </span>
        )}
      </div>

      {/* Полоса прогресса, а не только цифры: «5 из 7» читают глазами, а «почти всё»
          видят боковым зрением, не считая. */}
      {total > 0 && (
        <div className="mb-3 h-1.5 w-full overflow-hidden rounded-full bg-gray-200 dark:bg-gray-700">
          <div
            className="h-full rounded-full bg-purple-600 transition-[width] duration-200 dark:bg-purple-500"
            style={{ width: `${Math.round((done / total) * 100)}%` }}
          />
        </div>
      )}

      {isLoading && <p className="text-sm text-gray-500 dark:text-gray-400">{t('checklist.loading')}</p>}
      {isError && <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(error, t)}</p>}

      {!isLoading && !isError && total === 0 && (
        <p className="text-sm text-gray-400 dark:text-gray-500">{t('checklist.empty')}</p>
      )}

      {!isLoading && !isError && total > 0 && (
        <ul className="divide-y divide-gray-100 dark:divide-gray-700">
          {items.map((item) => (
            <li key={item.id} className="flex items-center gap-2 py-1.5 text-sm">
              <input
                type="checkbox"
                checked={item.done}
                disabled={!canManage}
                onChange={(event) => updateItem.mutate({ itemId: item.id, patch: { done: event.target.checked } })}
                aria-label={item.content}
                className="h-4 w-4 shrink-0 rounded border-gray-300 accent-purple-600 disabled:opacity-60 dark:border-gray-600"
              />
              {editingId === item.id ? (
                <>
                  <input
                    type="text"
                    value={editingText}
                    maxLength={500}
                    autoFocus
                    onChange={(event) => setEditingText(event.target.value)}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter') {
                        event.preventDefault()
                        onSaveText(item.id)
                      }
                      if (event.key === 'Escape') {
                        setEditingId(null)
                      }
                    }}
                    aria-label={t('checklist.editLabel')}
                    className={`${inputClass} min-w-0 flex-1`}
                  />
                  <button
                    type="button"
                    onClick={() => onSaveText(item.id)}
                    aria-label={t('checklist.save')}
                    title={t('checklist.save')}
                    className="shrink-0 text-gray-400 hover:text-purple-700 dark:hover:text-purple-400"
                  >
                    <Check className="h-4 w-4" aria-hidden="true" />
                  </button>
                  <button
                    type="button"
                    onClick={() => setEditingId(null)}
                    aria-label={t('checklist.cancel')}
                    title={t('checklist.cancel')}
                    className="shrink-0 text-gray-400 hover:text-gray-700 dark:hover:text-gray-200"
                  >
                    <X className="h-4 w-4" aria-hidden="true" />
                  </button>
                </>
              ) : (
                <>
                  <span
                    className={`min-w-0 flex-1 break-words ${
                      item.done
                        ? 'text-gray-400 line-through dark:text-gray-500'
                        : 'text-gray-900 dark:text-gray-100'
                    }`}
                  >
                    {item.content}
                  </span>
                  {canManage && (
                    <>
                      <button
                        type="button"
                        onClick={() => {
                          setEditingId(item.id)
                          setEditingText(item.content)
                        }}
                        aria-label={t('checklist.edit')}
                        title={t('checklist.edit')}
                        className="shrink-0 text-gray-400 hover:text-purple-700 dark:hover:text-purple-400"
                      >
                        <Pencil className="h-4 w-4" aria-hidden="true" />
                      </button>
                      <button
                        type="button"
                        onClick={() => deleteItem.mutate(item.id)}
                        disabled={deleteItem.isPending}
                        aria-label={t('checklist.delete')}
                        title={t('checklist.delete')}
                        className="shrink-0 text-gray-400 hover:text-red-600 disabled:opacity-60 dark:hover:text-red-400"
                      >
                        <Trash2 className="h-4 w-4" aria-hidden="true" />
                      </button>
                    </>
                  )}
                </>
              )}
            </li>
          ))}
        </ul>
      )}

      {canManage && (
        <form onSubmit={onAdd} className="mt-3 flex items-center gap-2">
          <input
            type="text"
            value={draft}
            maxLength={500}
            onChange={(event) => setDraft(event.target.value)}
            placeholder={t('checklist.addPlaceholder')}
            aria-label={t('checklist.add')}
            className={`${inputClass} min-w-0 flex-1`}
          />
          <button type="submit" disabled={addItem.isPending || !draft.trim()} className={primaryButtonClass}>
            {addItem.isPending ? t('checklist.adding') : t('checklist.add')}
          </button>
        </form>
      )}

      {(addItem.isError || updateItem.isError || deleteItem.isError) && (
        <p className="mt-2 text-sm text-red-600 dark:text-red-400">
          {getLocalizedErrorMessage(addItem.error ?? updateItem.error ?? deleteItem.error, t)}
        </p>
      )}
    </div>
  )
}
