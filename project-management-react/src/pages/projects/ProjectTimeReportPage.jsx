import { Download } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router-dom'
import { useProjectBySlug, useProjectMembers } from '../../api/projectsQueries'
import { downloadTimeReportCsv } from '../../api/reportsApi'
import { useTimeReport } from '../../api/reportsQueries'
import { Field, inputClass, primaryButtonClass, secondaryButtonClass } from '../../components/ui/FormKit'
import { TASK_NUMBER_BADGE_CLASS } from '../../lib/constants'
import { downloadBlob } from '../../lib/downloadBlob'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import {
  barPercent,
  currentMonthRange,
  filenameFromContentDisposition,
  formatReportHours,
  toIsoDate,
} from '../../lib/reports'
import { useToastStore } from '../../stores/toastStore'

// Сколько задач показывает таблица «на что ушло». Не пагинация, а потолок: отчёт отвечает
// на вопрос «куда делось время», и хвост из задач по пятнадцать минут на него не отвечает —
// за полным списком есть CSV.
const TOP_TASKS = 15

/**
 * Отчёт по времени (4.10).
 *
 * <p>Экран отвечает на три заранее известных вопроса — кто сколько отметил, на что ушло и
 * ровно ли шло, — а на все остальные отвечает кнопка «Скачать CSV»: сложить часы по-своему
 * можно только в таблице, и пытаться предугадать все разрезы прямо здесь бессмысленно.
 *
 * <p>Период по умолчанию — текущий месяц, а не последние 30 дней, которые подставил бы
 * сервер без параметров. Причина в форме: у полей с датами должно быть заполненное
 * значение, и «с 1 сентября по сегодня» человек читает как осмысленный период, а
 * «с 10 августа по сегодня» — как чей-то случайный выбор.
 */
export function ProjectTimeReportPage() {
  const { t, i18n } = useTranslation()
  const { projectSlug } = useParams()
  const pushToast = useToastStore((state) => state.pushToast)

  const { data: project } = useProjectBySlug(projectSlug)
  const projectId = project?.id
  const { data: members } = useProjectMembers(projectId)

  // Черновик формы и применённый фильтр — разные состояния: иначе отчёт перезапрашивался
  // бы на каждое нажатие в поле даты, включая заведомо невалидные промежуточные значения
  // вроде «2026-09-0».
  const [draft, setDraft] = useState(() => ({ ...currentMonthRange(), userId: '' }))
  const [filters, setFilters] = useState(draft)
  const [downloading, setDownloading] = useState(false)

  const { data: report, isLoading, isError, error } = useTimeReport(projectId, filters)

  const maxUserHours = useMemo(
    () => Math.max(0, ...(report?.byUser ?? []).map((row) => Number(row.hours))),
    [report],
  )
  const maxTaskHours = useMemo(
    () => Math.max(0, ...(report?.byTask ?? []).map((row) => Number(row.hours))),
    [report],
  )
  const maxDayHours = useMemo(
    () => Math.max(0, ...(report?.byDay ?? []).map((row) => Number(row.hours))),
    [report],
  )

  function applyFilters(event) {
    event.preventDefault()
    setFilters(draft)
  }

  function applyPreset(range) {
    const next = { ...draft, ...range }
    setDraft(next)
    setFilters(next)
  }

  async function onDownloadCsv() {
    setDownloading(true)
    try {
      const { blob, contentDisposition } = await downloadTimeReportCsv(projectId, filters)
      downloadBlob(blob, filenameFromContentDisposition(contentDisposition, `time-report-${projectSlug}.csv`))
    } catch (downloadError) {
      pushToast(getLocalizedErrorMessage(downloadError, t), 'error')
    } finally {
      setDownloading(false)
    }
  }

  return (
    <div className="mx-auto max-w-6xl space-y-4 px-4 py-6">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-xl font-semibold text-gray-900 dark:text-gray-100">{t('timeReport.title')}</h1>
        <p className="text-sm text-gray-500 dark:text-gray-400">{t('timeReport.hint')}</p>
        <button
          type="button"
          onClick={onDownloadCsv}
          disabled={downloading || !projectId}
          className={`${secondaryButtonClass} ml-auto flex shrink-0 items-center gap-2`}
        >
          <Download className="h-4 w-4" aria-hidden="true" />
          {downloading ? t('timeReport.downloading') : t('timeReport.downloadCsv')}
        </button>
      </div>

      <form
        onSubmit={applyFilters}
        className="flex flex-wrap items-end gap-3 rounded-lg border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-800"
      >
        <div className="w-40">
          <Field label={t('timeReport.from')}>
            <input
              type="date"
              className={inputClass}
              value={draft.from}
              onChange={(event) => setDraft({ ...draft, from: event.target.value })}
            />
          </Field>
        </div>
        <div className="w-40">
          <Field label={t('timeReport.to')}>
            <input
              type="date"
              className={inputClass}
              value={draft.to}
              onChange={(event) => setDraft({ ...draft, to: event.target.value })}
            />
          </Field>
        </div>
        <div className="w-56">
          <Field label={t('timeReport.member')}>
            <select
              className={inputClass}
              value={draft.userId}
              onChange={(event) => setDraft({ ...draft, userId: event.target.value })}
            >
              <option value="">{t('timeReport.allMembers')}</option>
              {members?.map((member) => (
                <option key={member.userId} value={member.userId}>
                  {member.lastName} {member.firstName}
                </option>
              ))}
            </select>
          </Field>
        </div>
        <button type="submit" className={primaryButtonClass}>
          {t('timeReport.apply')}
        </button>
        <div className="flex items-center gap-3 text-xs">
          <button
            type="button"
            onClick={() => applyPreset(currentMonthRange())}
            className="text-purple-600 hover:underline dark:text-purple-400"
          >
            {t('timeReport.presetThisMonth')}
          </button>
          <button
            type="button"
            onClick={() => applyPreset(previousMonthRange())}
            className="text-purple-600 hover:underline dark:text-purple-400"
          >
            {t('timeReport.presetPreviousMonth')}
          </button>
          <button
            type="button"
            onClick={() => applyPreset(lastDaysRange(30))}
            className="text-purple-600 hover:underline dark:text-purple-400"
          >
            {t('timeReport.presetLast30')}
          </button>
        </div>
      </form>

      {isLoading && <p className="text-gray-500 dark:text-gray-400">{t('timeReport.loading')}</p>}
      {isError && <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(error, t)}</p>}

      {report && !isError && (
        <>
          <div className="rounded-lg border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-800">
            <p className="text-sm text-gray-500 dark:text-gray-400">
              {t('timeReport.periodLabel', {
                from: formatDay(report.from, i18n.language),
                to: formatDay(report.to, i18n.language),
              })}
            </p>
            <p className="mt-1 text-3xl font-semibold tabular-nums text-gray-900 dark:text-gray-100">
              {t('timeReport.hoursValue', { hours: formatReportHours(report.totalHours) })}
            </p>
            <DayStrip days={report.byDay} max={maxDayHours} locale={i18n.language} />
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <Panel title={t('timeReport.byMember')} empty={report.byUser.length === 0} emptyText={t('timeReport.empty')}>
              {report.byUser.map((row) => (
                <BarRow
                  key={row.user.id}
                  label={`${row.user.lastName} ${row.user.firstName}`}
                  hint={t('timeReport.entries', { count: row.entryCount })}
                  hours={row.hours}
                  percent={barPercent(row.hours, maxUserHours)}
                />
              ))}
            </Panel>

            <Panel title={t('timeReport.byTask')} empty={report.byTask.length === 0} emptyText={t('timeReport.empty')}>
              {report.byTask.slice(0, TOP_TASKS).map((row) => (
                <BarRow
                  key={row.taskId}
                  label={row.title}
                  badge={row.taskNumber}
                  to={`/projects/${projectSlug}/tasks/${row.taskNumber}`}
                  hours={row.hours}
                  percent={barPercent(row.hours, maxTaskHours)}
                />
              ))}
              {report.byTask.length > TOP_TASKS && (
                <p className="px-4 py-2 text-xs text-gray-400 dark:text-gray-500">
                  {t('timeReport.moreTasks', { count: report.byTask.length - TOP_TASKS })}
                </p>
              )}
            </Panel>
          </div>
        </>
      )}
    </div>
  )
}

/**
 * Полоса «как распределялось внутри периода». Столбик на каждый день, включая пустые —
 * иначе выходные схлопываются, и неделя из двух рабочих дней выглядит как полная.
 */
function DayStrip({ days, max, locale }) {
  const { t } = useTranslation()
  if (!days?.length) {
    return null
  }

  return (
    <>
      <div className="mt-4 flex h-16 items-end gap-px" aria-hidden="true">
        {days.map((day) => (
          <div
            key={day.day}
            title={`${formatDay(day.day, locale)} — ${t('timeReport.hoursValue', { hours: formatReportHours(day.hours) })}`}
            className="min-w-0 flex-1 rounded-t-sm bg-purple-500/70 dark:bg-purple-500/60"
            // Минимум в 2% — чтобы день с нулём остался видимой засечкой шкалы, а не
            // исчезнувшим столбиком: пропуск в полосе читается как «данных нет», а не
            // «в этот день не работали».
            style={{ height: `${Math.max(barPercent(day.hours, max), 2)}%` }}
          />
        ))}
      </div>
      {/* Подписи краёв: без них полоса — это набор столбиков без шкалы, и «когда именно
          был тот высокий день» приходится выяснивать наведением мыши. */}
      <div className="mt-1 flex items-center justify-between text-xs text-gray-400 dark:text-gray-500">
        <span>{formatDay(days[0].day, locale)}</span>
        <span>{t('timeReport.maxDay', { hours: formatReportHours(max) })}</span>
        <span>{formatDay(days[days.length - 1].day, locale)}</span>
      </div>
    </>
  )
}

function Panel({ title, empty, emptyText, children }) {
  return (
    <div className="rounded-lg border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
      <h2 className="border-b border-gray-200 px-4 py-3 text-sm font-semibold text-gray-900 dark:border-gray-700 dark:text-gray-100">
        {title}
      </h2>
      {empty ? (
        <p className="px-4 py-6 text-center text-sm text-gray-400 dark:text-gray-500">{emptyText}</p>
      ) : (
        <div className="divide-y divide-gray-100 dark:divide-gray-700/60">{children}</div>
      )}
    </div>
  )
}

function BarRow({ label, hint, badge, to, hours, percent }) {
  const { t } = useTranslation()
  const title = (
    <span className="min-w-0 flex-1 truncate text-sm text-gray-900 dark:text-gray-100">{label}</span>
  )

  return (
    <div className="px-4 py-2">
      <div className="flex items-center gap-2">
        {badge != null && <span className={TASK_NUMBER_BADGE_CLASS}>#{badge}</span>}
        {to ? (
          <Link to={to} className="min-w-0 flex-1 truncate text-sm text-gray-900 hover:underline dark:text-gray-100">
            {label}
          </Link>
        ) : (
          title
        )}
        {hint && <span className="shrink-0 text-xs text-gray-400 dark:text-gray-500">{hint}</span>}
        <span className="shrink-0 text-sm font-medium tabular-nums text-gray-900 dark:text-gray-100">
          {t('timeReport.hoursValue', { hours: formatReportHours(hours) })}
        </span>
      </div>
      <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-gray-100 dark:bg-gray-700">
        <div className="h-full rounded-full bg-purple-500" style={{ width: `${percent}%` }} />
      </div>
    </div>
  )
}

// Даты приходят как "2026-09-08" (LocalDate) — разбираем по частям, а не new Date(строка):
// последний считает её UTC-полночью и в отрицательных поясах показывает предыдущий день.
function formatDay(isoDate, locale) {
  const [year, month, day] = String(isoDate).split('-').map(Number)
  return new Date(year, month - 1, day).toLocaleDateString(locale, { day: 'numeric', month: 'short' })
}

function previousMonthRange(today = new Date()) {
  const first = new Date(today.getFullYear(), today.getMonth() - 1, 1)
  const last = new Date(today.getFullYear(), today.getMonth(), 0)
  return { from: toIsoDate(first), to: toIsoDate(last) }
}

function lastDaysRange(days, today = new Date()) {
  const from = new Date(today.getFullYear(), today.getMonth(), today.getDate() - (days - 1))
  return { from: toIsoDate(from), to: toIsoDate(today) }
}
