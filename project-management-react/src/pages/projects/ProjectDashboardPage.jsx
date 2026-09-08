import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router-dom'
import { useProjectBySlug } from '../../api/projectsQueries'
import { useDashboard } from '../../api/reportsQueries'
import { useSprints } from '../../api/sprintsQueries'
import { inputClass } from '../../components/ui/FormKit'
import { taskStatusAccentClass } from '../../lib/constants'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { barPercent, burndownPolyline, idealPolyline } from '../../lib/reports'
import { formatSprintRange } from '../../lib/sprints'
import { writeFilters } from '../../lib/taskFilters'

// Система координат графика burndown. Числа условные: SVG растягивается по ширине
// контейнера через viewBox, поэтому «100 на 50» — это пропорции, а не пиксели.
const CHART = { width: 600, height: 200 }

/**
 * Дашборд проекта (4.11).
 *
 * <p>Отвечает на четыре вопроса, и порядок блоков — это порядок, в котором их задают:
 * сколько всего работы и сколько открыто, где она скопилась (статусы), на ком висит
 * (исполнители), успеваем ли в текущий заход (burndown) и где задачи застревают дольше
 * всего (среднее время в статусе).
 *
 * <p>Графики нарисованы вручную — полосами на div'ах и одним SVG. Библиотека графиков
 * (recharts и подобные) добавила бы к бандлу сотню килобайт ради пяти диаграмм, четыре из
 * которых — горизонтальные полосы, то есть ровно то, что страница спринтов уже рисует
 * одним div'ом с шириной в процентах.
 *
 * <p>Все блоки, кроме burndown, считаются по всему проекту и от выбранного спринта не
 * зависят: спринту нужен отрезок времени, остальным — нет, а «дашборд, который меняется
 * весь при переключении спринта» превратил бы страницу спринтов во второй такой же экран.
 */
export function ProjectDashboardPage() {
  const { t, i18n } = useTranslation()
  const { projectSlug } = useParams()

  const { data: project } = useProjectBySlug(projectSlug)
  const projectId = project?.id
  const { data: sprints } = useSprints(projectId)

  // Пустая строка — «пусть решит сервер»: он берёт идущий спринт, а если такого нет —
  // ближайший к сегодняшнему дню. Подставлять этот выбор здесь значило бы держать его в
  // двух местах и однажды разойтись.
  const [sprintId, setSprintId] = useState('')
  const { data: dashboard, isLoading, isError, error } = useDashboard(projectId, sprintId || undefined)

  if (isLoading) {
    return <p className="px-4 py-8 text-gray-500 dark:text-gray-400">{t('dashboard.loading')}</p>
  }
  if (isError) {
    return <p className="px-4 py-8 text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(error, t)}</p>
  }
  if (!dashboard) {
    return null
  }

  const closedTasks = dashboard.totalTasks - dashboard.openTasks
  const maxStatusCount = Math.max(0, ...dashboard.statusDistribution.map((row) => row.count))
  const maxAssigneeCount = Math.max(0, ...dashboard.assigneeDistribution.map((row) => row.totalCount))

  return (
    <div className="mx-auto max-w-6xl space-y-4 px-4 py-6">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-xl font-semibold text-gray-900 dark:text-gray-100">{t('dashboard.title')}</h1>
        <p className="text-sm text-gray-500 dark:text-gray-400">{t('dashboard.hint')}</p>
      </div>

      <div className="grid gap-4 sm:grid-cols-3">
        <Kpi label={t('dashboard.totalTasks')} value={dashboard.totalTasks} />
        <Kpi label={t('dashboard.openTasks')} value={dashboard.openTasks} />
        <Kpi
          label={t('dashboard.closedTasks')}
          value={closedTasks}
          hint={
            dashboard.totalTasks > 0
              ? t('dashboard.closedShare', { percent: Math.round((closedTasks / dashboard.totalTasks) * 100) })
              : null
          }
        />
      </div>

      <Card
        title={t('dashboard.burndown')}
        action={
          sprints?.length > 0 && (
            <select
              value={sprintId}
              onChange={(event) => setSprintId(event.target.value)}
              className={`${inputClass} w-auto py-1 text-xs`}
              aria-label={t('dashboard.sprintSelector')}
            >
              <option value="">{t('dashboard.currentSprint')}</option>
              {sprints.map((sprint) => (
                <option key={sprint.id} value={sprint.id}>
                  {sprint.name}
                </option>
              ))}
            </select>
          )
        }
      >
        {dashboard.burndown ? (
          <Burndown burndown={dashboard.burndown} projectSlug={projectSlug} locale={i18n.language} />
        ) : (
          <p className="px-4 py-8 text-center text-sm text-gray-400 dark:text-gray-500">
            {t('dashboard.noSprints')}
          </p>
        )}
      </Card>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card title={t('dashboard.byStatus')}>
          <div className="space-y-3 p-4">
            {dashboard.statusDistribution.map((row) => (
              <div key={row.status}>
                <div className="flex items-center gap-2 text-sm">
                  <span className="min-w-0 flex-1 truncate text-gray-900 dark:text-gray-100">
                    {t(`tasks.status.${row.status}`)}
                  </span>
                  <span className="shrink-0 tabular-nums text-gray-500 dark:text-gray-400">{row.count}</span>
                </div>
                <div className="mt-1 h-2 overflow-hidden rounded-full bg-gray-100 dark:bg-gray-700">
                  {/* Цвет полосы — тот же, что у колонки канбана: график статусов, у
                      которого свой набор цветов, пришлось бы каждый раз сверять с доской. */}
                  <div
                    className={`h-full rounded-full ${taskStatusAccentClass(row.status)}`}
                    style={{ width: `${barPercent(row.count, maxStatusCount)}%` }}
                  />
                </div>
              </div>
            ))}
          </div>
        </Card>

        <Card title={t('dashboard.byAssignee')}>
          {dashboard.assigneeDistribution.length === 0 ? (
            <p className="px-4 py-6 text-center text-sm text-gray-400 dark:text-gray-500">{t('dashboard.empty')}</p>
          ) : (
            <div className="space-y-3 p-4">
              {dashboard.assigneeDistribution.map((row) => (
                <div key={row.user?.id ?? 'unassigned'}>
                  <div className="flex items-center gap-2 text-sm">
                    <span className="min-w-0 flex-1 truncate text-gray-900 dark:text-gray-100">
                      {row.user ? `${row.user.lastName} ${row.user.firstName}` : t('dashboard.unassigned')}
                    </span>
                    <span className="shrink-0 tabular-nums text-gray-500 dark:text-gray-400">
                      {t('dashboard.openOfTotal', { open: row.openCount, total: row.totalCount })}
                    </span>
                  </div>
                  {/* Две полосы одна в другой: светлая — всё, что на человеке было,
                      насыщенная — то, что ещё открыто. Так видно и объём, и долг. */}
                  <div className="mt-1 h-2 overflow-hidden rounded-full bg-gray-100 dark:bg-gray-700">
                    <div
                      className="h-full rounded-full bg-purple-200 dark:bg-purple-900/60"
                      style={{ width: `${barPercent(row.totalCount, maxAssigneeCount)}%` }}
                    >
                      <div
                        className="h-full rounded-full bg-purple-500"
                        style={{ width: `${barPercent(row.openCount, row.totalCount)}%` }}
                      />
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </Card>
      </div>

      <Card title={t('dashboard.timeInStatus')} subtitle={t('dashboard.timeInStatusHint')}>
        <div className="grid gap-3 p-4 sm:grid-cols-3">
          {dashboard.averageTimeInStatus.map((row) => (
            <div key={row.status} className="rounded-md border border-gray-200 p-3 dark:border-gray-700">
              <p className="text-xs text-gray-500 dark:text-gray-400">{t(`tasks.status.${row.status}`)}</p>
              <p className="mt-1 text-lg font-semibold tabular-nums text-gray-900 dark:text-gray-100">
                {row.averageHours == null ? '—' : formatDuration(row.averageHours, t)}
              </p>
              {/* Число наблюдений рядом обязательно: «в FEEDBACK в среднем 40 часов» по
                  двум переходам и по двумстам — разные утверждения. */}
              <p className="text-xs text-gray-400 dark:text-gray-500">
                {t('dashboard.samples', { count: row.sampleCount })}
              </p>
            </div>
          ))}
        </div>
      </Card>
    </div>
  )
}

function Kpi({ label, value, hint }) {
  return (
    <div className="rounded-lg border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-800">
      <p className="text-sm text-gray-500 dark:text-gray-400">{label}</p>
      <p className="mt-1 text-3xl font-semibold tabular-nums text-gray-900 dark:text-gray-100">{value}</p>
      {hint && <p className="text-xs text-gray-400 dark:text-gray-500">{hint}</p>}
    </div>
  )
}

function Card({ title, subtitle, action, children }) {
  return (
    <div className="rounded-lg border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
      <div className="flex items-center gap-3 border-b border-gray-200 px-4 py-3 dark:border-gray-700">
        <h2 className="text-sm font-semibold text-gray-900 dark:text-gray-100">{title}</h2>
        {subtitle && <p className="min-w-0 truncate text-xs text-gray-500 dark:text-gray-400">{subtitle}</p>}
        {action && <div className="ml-auto shrink-0">{action}</div>}
      </div>
      {children}
    </div>
  )
}

/**
 * График выгорания. Две линии: пунктирная серая — план (от полного объёма в первый день до
 * нуля в последний), сплошная фиолетовая — факт. Факт обрывается на сегодняшнем дне:
 * будущие точки приезжают с remaining = null, и продолжать по ним линию значило бы
 * показывать спринт выполненным до срока.
 */
function Burndown({ burndown, projectSlug, locale }) {
  const { t } = useTranslation()
  const box = { ...CHART, scope: burndown.scope }
  const actual = burndownPolyline(burndown.points, box)
  const ideal = idealPolyline(burndown.points, box)

  return (
    <div className="p-4">
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <Link
          to={`/projects/${projectSlug}/tasks?${writeFilters({ sprint: burndown.sprint.id, sort: 'NUMBER' })}`}
          className="text-sm font-medium text-purple-600 hover:underline dark:text-purple-400"
        >
          {burndown.sprint.name}
        </Link>
        <span className="text-xs text-gray-500 dark:text-gray-400">
          {formatSprintRange({ startDate: burndown.startDate, endDate: burndown.endDate }, locale)}
        </span>
        <span className="text-xs text-gray-500 dark:text-gray-400">
          · {t('dashboard.scope', { count: burndown.scope })}
        </span>
      </div>

      {burndown.scope === 0 ? (
        <p className="py-8 text-center text-sm text-gray-400 dark:text-gray-500">{t('dashboard.emptySprint')}</p>
      ) : (
        <>
          {/* Подписи шкалы стоят рядом с SVG, а не внутри него: preserveAspectRatio="none"
              растягивает содержимое по ширине контейнера, и текст внутри растянулся бы
              вместе с линиями. */}
          <div className="mt-3 flex items-stretch gap-2">
            <div className="flex w-6 shrink-0 flex-col justify-between py-px text-right text-xs tabular-nums text-gray-400 dark:text-gray-500">
              <span>{burndown.scope}</span>
              <span>0</span>
            </div>
            <svg
              viewBox={`0 0 ${CHART.width} ${CHART.height}`}
              preserveAspectRatio="none"
              className="h-48 w-full"
              role="img"
              aria-label={t('dashboard.burndownAlt', { sprint: burndown.sprint.name })}
            >
              {/* Горизонтальные засечки по четвертям объёма — без них глаз не отличает
                  падение на одну задачу от падения на десять. */}
              {[0, 0.25, 0.5, 0.75, 1].map((fraction) => (
                <line
                  key={fraction}
                  x1="0"
                  x2={CHART.width}
                  y1={CHART.height * fraction}
                  y2={CHART.height * fraction}
                  className="stroke-gray-200 dark:stroke-gray-700"
                  strokeWidth="1"
                  vectorEffect="non-scaling-stroke"
                />
              ))}
              {ideal && (
                <polyline
                  points={ideal}
                  fill="none"
                  strokeDasharray="6 4"
                  strokeWidth="2"
                  vectorEffect="non-scaling-stroke"
                  className="stroke-gray-400 dark:stroke-gray-500"
                />
              )}
              {actual && (
                <polyline
                  points={actual}
                  fill="none"
                  strokeWidth="2"
                  strokeLinejoin="round"
                  vectorEffect="non-scaling-stroke"
                  className="stroke-purple-500"
                />
              )}
            </svg>
          </div>

          {/* pl-8 — под ширину колонки подписей шкалы слева, чтобы даты стояли ровно под
              краями графика, а не под всей карточкой. */}
          <div className="flex items-center justify-between pl-8 text-xs text-gray-400 dark:text-gray-500">
            <span>{burndown.startDate}</span>
            <span className="flex items-center gap-3">
              <span className="flex items-center gap-1">
                <span className="inline-block h-0.5 w-4 bg-purple-500" aria-hidden="true" />
                {t('dashboard.actual')}
              </span>
              <span className="flex items-center gap-1">
                <span className="inline-block h-0.5 w-4 bg-gray-400" aria-hidden="true" />
                {t('dashboard.ideal')}
              </span>
            </span>
            <span>{burndown.endDate}</span>
          </div>
        </>
      )}
    </div>
  )
}

/**
 * Среднее время в статусе: часами, пока их меньше двух суток, дальше — днями. «73.4 часа»
 * не читается как ничто, «3 дня» читается сразу.
 */
function formatDuration(hours, t) {
  const value = Number(hours)
  if (value >= 48) {
    return t('dashboard.days', { count: Math.round(value / 24) })
  }
  return t('dashboard.hours', { hours: Math.round(value * 10) / 10 })
}
