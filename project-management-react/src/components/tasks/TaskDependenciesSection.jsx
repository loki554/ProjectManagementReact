import { Ban, OctagonAlert } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { useAddDependency, useRemoveDependency, useTaskDependencies } from '../../api/dependenciesQueries'
import { inputClass, primaryButtonClass } from '../ui/FormKit'
import { TASK_NUMBER_BADGE_CLASS, taskStatusBadgeClass } from '../../lib/constants'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'

// Закрытые статусы — зеркало TaskStatus.INACTIVE на бэкенде: блокер в одном из них уже
// никому не мешает, и подсвечивать его как препятствие было бы враньём.
const CLOSED_STATUSES = ['DONE', 'REJECTED']

function DependencyList({ direction, items, projectSlug, canManage, onRemove, removing }) {
  const { t } = useTranslation()

  if (items.length === 0) {
    return <p className="py-1.5 text-sm text-gray-400 dark:text-gray-500">{t(`tasks.dependencies.${direction}Empty`)}</p>
  }

  return (
    <ul className="divide-y divide-gray-100 dark:divide-gray-700">
      {items.map((item) => {
        const open = !CLOSED_STATUSES.includes(item.status)
        return (
          <li key={item.id} className="flex items-center gap-2 py-1.5 text-sm">
            {/* Иконка только у незакрытого блокера и только в списке «мешают мне»:
                в списке «мешаю я» препятствие — не моя проблема, а чужая. */}
            {direction === 'blockedBy' && open && (
              <OctagonAlert
                role="img"
                className="h-4 w-4 shrink-0 text-amber-500"
                aria-label={t('tasks.dependencies.openBlockerHint')}
              />
            )}
            <Link
              to={`/projects/${projectSlug}/tasks/${item.taskNumber}`}
              className="flex min-w-0 flex-1 items-center gap-2 hover:text-purple-700 dark:hover:text-purple-400"
            >
              <span className={TASK_NUMBER_BADGE_CLASS}>#{item.taskNumber}</span>
              <span className={`truncate ${open ? 'text-gray-900 dark:text-gray-100' : 'text-gray-400 line-through dark:text-gray-500'}`}>
                {item.title}
              </span>
            </Link>
            <span className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-medium ${taskStatusBadgeClass(item.status)}`}>
              {t(`tasks.status.${item.status}`)}
            </span>
            {canManage && (
              <button
                type="button"
                onClick={() => onRemove(item.id)}
                disabled={removing}
                aria-label={t('tasks.dependencies.remove')}
                title={t('tasks.dependencies.remove')}
                className="shrink-0 text-gray-400 hover:text-red-600 disabled:opacity-60 dark:hover:text-red-400"
              >
                <Ban className="h-4 w-4" aria-hidden="true" />
              </button>
            )}
          </li>
        )
      })}
    </ul>
  )
}

/**
 * Панель зависимостей задачи (4.8): «мешают мне» и «мешаю я».
 *
 * Добавление — номером задачи (#N), а не выбором из списка: селект пришлось бы наполнять
 * всеми задачами проекта (на паре тысяч это тот самый полный список, от которого ушли в 3.3),
 * а номер человек и так видит на каждой карточке и в адресной строке — им задачи в проекте и
 * называют. Ошибиться номером не страшно: несуществующий вернётся обычным «задача не найдена».
 *
 * Обе стороны добавляются одинаково, потому что связь одна и та же, прочитанная с разных
 * концов (см. dependenciesApi). Без второй формы «эта блокирует #7» пришлось бы заводить,
 * открыв #7, — то есть уходить со страницы, на которой человек сейчас думает.
 */
export function TaskDependenciesSection({ taskId, projectId, projectSlug, canManage }) {
  const { t } = useTranslation()
  const { data, isLoading, isError, error } = useTaskDependencies(taskId)
  const addDependency = useAddDependency(projectId, taskId)
  const removeDependency = useRemoveDependency(projectId, taskId)
  const [draft, setDraft] = useState({ blockedBy: '', blocks: '' })

  function onAdd(event, direction) {
    event.preventDefault()
    const taskNumber = Number(draft[direction])
    if (!Number.isInteger(taskNumber) || taskNumber <= 0) {
      return
    }
    addDependency.mutate(
      { taskNumber, direction },
      { onSuccess: () => setDraft((previous) => ({ ...previous, [direction]: '' })) },
    )
  }

  const sections = ['blockedBy', 'blocks']

  return (
    <div className="rounded-lg border border-gray-200 bg-white p-6 dark:border-gray-700 dark:bg-gray-800">
      <h2 className="mb-3 text-sm font-semibold text-gray-900 dark:text-gray-100">{t('tasks.dependencies.title')}</h2>

      {isLoading && <p className="text-sm text-gray-500 dark:text-gray-400">{t('tasks.dependencies.loading')}</p>}
      {isError && <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(error, t)}</p>}

      {!isLoading && !isError && data && (
        <div className="space-y-4">
          {sections.map((direction) => (
            <div key={direction}>
              <h3 className="text-xs font-medium tracking-wide text-gray-500 uppercase dark:text-gray-400">
                {t(`tasks.dependencies.${direction}Title`)}
              </h3>
              <DependencyList
                direction={direction}
                items={data[direction]}
                projectSlug={projectSlug}
                canManage={canManage}
                onRemove={(otherTaskId) => removeDependency.mutate({ otherTaskId, direction })}
                removing={removeDependency.isPending}
              />
              {canManage && (
                <form onSubmit={(event) => onAdd(event, direction)} className="mt-2 flex items-center gap-2">
                  {/* Поле узкое и с ведущей решёткой: сюда вводят номер задачи, а не текст,
                      и выглядеть оно должно как «#», а не как ещё одно поле поиска. */}
                  <span className="text-sm text-gray-400 dark:text-gray-500">#</span>
                  <input
                    type="number"
                    min="1"
                    step="1"
                    value={draft[direction]}
                    onChange={(event) => setDraft((previous) => ({ ...previous, [direction]: event.target.value }))}
                    aria-label={t(`tasks.dependencies.${direction}AddLabel`)}
                    placeholder={t('tasks.dependencies.numberPlaceholder')}
                    className={`${inputClass} w-28`}
                  />
                  <button type="submit" disabled={addDependency.isPending} className={primaryButtonClass}>
                    {addDependency.isPending ? t('tasks.dependencies.adding') : t('tasks.dependencies.add')}
                  </button>
                </form>
              )}
            </div>
          ))}
        </div>
      )}

      {addDependency.isError && (
        <p className="mt-2 text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(addDependency.error, t)}</p>
      )}
      {removeDependency.isError && (
        <p className="mt-2 text-sm text-red-600 dark:text-red-400">
          {getLocalizedErrorMessage(removeDependency.error, t)}
        </p>
      )}
    </div>
  )
}
