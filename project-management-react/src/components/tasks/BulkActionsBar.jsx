import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { inputClass, primaryButtonClass, secondaryButtonClass } from '../ui/FormKit'
import {
  BULK_NO_TAG,
  BULK_UNASSIGN,
  EMPTY_BULK_FORM,
  buildBulkPayload,
  isEmptyBulkPayload,
} from '../../lib/bulkTasks'
import { TASK_STATUSES } from '../../lib/constants'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'

/**
 * Панель массовых операций над выделенными задачами (4.6): один статус/исполнитель/тэг/срок
 * на весь набор.
 *
 * <p>Поля собираются и отправляются одним нажатием «Применить», а не применяются каждое
 * сразу по выбору в списке. Применение по выбору экономит клик, но делает промах мышью
 * необратимой правкой полусотни задач — а отменить её нечем: сервер уже разослал
 * уведомления, лента активности уже записала полсотни событий. Заодно это позволяет
 * поменять статус и исполнителя одним запросом, а не двумя.
 *
 * <p>Панель — часть потока страницы, а не плавающая полоса поверх таблицы: перекрывать
 * последние строки списка ровно в тот момент, когда человек по этому списку и выделяет,
 * не стоит того, чтобы сэкономить строку вёрстки.
 */
export function BulkActionsBar({ selectedCount, members, tags, onApply, onCancel, isPending, error }) {
  const { t } = useTranslation()
  const [form, setForm] = useState(EMPTY_BULK_FORM)

  const payload = buildBulkPayload(form)
  const nothingChosen = isEmptyBulkPayload(payload)

  function set(field, value) {
    setForm((prev) => ({ ...prev, [field]: value }))
  }

  function handleSubmit(event) {
    event.preventDefault()
    if (nothingChosen || isPending) {
      return
    }
    // Сбрасывать форму здесь незачем: успешное применение снимает выделение, а без
    // выделения панель размонтируется вместе со всем своим состоянием.
    onApply(payload)
  }

  return (
    <form
      onSubmit={handleSubmit}
      className="flex flex-wrap items-center gap-2 rounded-lg border border-purple-200 bg-purple-50 px-3 py-2 dark:border-purple-900 dark:bg-purple-950/40"
    >
      <span className="text-sm font-medium whitespace-nowrap text-purple-900 dark:text-purple-200">
        {t('taskList.bulk.selected', { count: selectedCount })}
      </span>

      <select
        aria-label={t('taskList.bulk.statusLabel')}
        value={form.status}
        onChange={(event) => set('status', event.target.value)}
        className={`${inputClass} w-44`}
      >
        <option value="">{t('taskList.bulk.keepStatus')}</option>
        {TASK_STATUSES.map((status) => (
          <option key={status} value={status}>
            {t(`tasks.status.${status}`)}
          </option>
        ))}
      </select>

      <select
        aria-label={t('taskList.bulk.assigneeLabel')}
        value={form.assignee}
        onChange={(event) => set('assignee', event.target.value)}
        className={`${inputClass} w-52`}
      >
        <option value="">{t('taskList.bulk.keepAssignee')}</option>
        <option value={BULK_UNASSIGN}>{t('taskList.bulk.unassign')}</option>
        {members?.map((member) => (
          <option key={member.userId} value={member.userId}>
            {member.lastName} {member.firstName}
          </option>
        ))}
      </select>

      <select
        aria-label={t('taskList.bulk.tagLabel')}
        value={form.tag}
        onChange={(event) => set('tag', event.target.value)}
        className={`${inputClass} w-44`}
      >
        <option value="">{t('taskList.bulk.keepTag')}</option>
        <option value={BULK_NO_TAG}>{t('taskList.bulk.noTag')}</option>
        {tags?.map((tag) => (
          <option key={tag.id} value={tag.id}>
            {tag.name}
          </option>
        ))}
      </select>

      {/* Поле даты гаснет под галочкой «снять срок» — не потому, что иначе сломается
          (флаг очистки сильнее значения и на клиенте, и на сервере), а чтобы не
          предлагать ввести то, что заведомо не будет использовано. */}
      <input
        type="datetime-local"
        aria-label={t('taskList.bulk.dueDateLabel')}
        value={form.dueDate}
        disabled={form.clearDueDate}
        onChange={(event) => set('dueDate', event.target.value)}
        className={`${inputClass} w-52 disabled:opacity-50`}
      />
      <label className="flex items-center gap-1.5 text-sm whitespace-nowrap text-gray-700 dark:text-gray-300">
        <input
          type="checkbox"
          checked={form.clearDueDate}
          onChange={(event) => set('clearDueDate', event.target.checked)}
          className="h-4 w-4 accent-purple-600"
        />
        {t('taskList.bulk.clearDueDate')}
      </label>

      <div className="ml-auto flex items-center gap-2">
        {error && (
          <span className="text-sm text-red-600 dark:text-red-400">
            {getLocalizedErrorMessage(error, t)}
          </span>
        )}
        <button type="button" onClick={onCancel} className={secondaryButtonClass}>
          {t('taskList.bulk.cancel')}
        </button>
        <button type="submit" disabled={nothingChosen || isPending} className={primaryButtonClass}>
          {isPending ? t('taskList.bulk.applying') : t('taskList.bulk.apply')}
        </button>
      </div>
    </form>
  )
}
