import { BookOpen, Braces, Download, Table } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useParams } from 'react-router-dom'
import { downloadTasksCsv, downloadTasksJson, downloadWikiMarkdown } from '../../api/exportApi'
import { useProjectBySlug } from '../../api/projectsQueries'
import { useProjectWiki } from '../../api/wikiQueries'
import { secondaryButtonClass } from '../../components/ui/FormKit'
import { saveDownloadedFile } from '../../lib/downloadBlob'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { useToastStore } from '../../stores/toastStore'

/**
 * Выгрузка данных проекта (4.12).
 *
 * <p>Одна страница на все три файла, а не по кнопке на своём экране (CSV — в списке задач,
 * Markdown — в вики). Причина в вопросе, который сюда приводит: «как забрать свои данные» —
 * это один вопрос, и ответом на него должно быть одно место, а не память о том, на какой
 * странице какая кнопка. Кнопка в списке задач вдобавок обещала бы выгрузку с текущими
 * фильтрами, а выгружается проект целиком (см. ExportService).
 *
 * <p>Скачивание идёт через apiClient blob'ом, а не ссылкой: файлы требуют авторизации.
 * Поэтому у каждой кнопки своё состояние «готовим файл» — на большом проекте CSV собирается
 * не мгновенно, и кнопка без отклика читается как сломанная.
 */
export function ProjectExportPage() {
  const { t } = useTranslation()
  const { projectSlug } = useParams()
  const pushToast = useToastStore((state) => state.pushToast)

  const { data: project } = useProjectBySlug(projectSlug)
  const projectId = project?.id
  // Вики запрашивается ради одного признака: пустую страницу выгружать незачем, и кнопка,
  // отдающая файл на ноль байт, — худший способ об этом сообщить.
  //
  // Пусто — только когда ответ действительно пришёл пустым (isSuccess), а не пока он ещё
  // едет: «данных нет» и «данные ещё не приехали» — разные утверждения, и подпись «вики
  // пока пуста», мигающая на каждом открытии страницы, врёт ровно про то, ради чего этот
  // запрос здесь и сделан. Если запрос упал, кнопка остаётся рабочей: пусть скачивание
  // ответит настоящей ошибкой, а не выдуманным «пусто».
  const { data: wiki, isLoading: wikiLoading, isSuccess: wikiLoaded } = useProjectWiki(projectId)
  const wikiIsEmpty = wikiLoaded && !wiki.content?.trim()

  const [pending, setPending] = useState(null)

  async function download(kind, request, fallbackName) {
    setPending(kind)
    try {
      saveDownloadedFile(await request(projectId), fallbackName)
    } catch (error) {
      pushToast(getLocalizedErrorMessage(error, t), 'error')
    } finally {
      setPending(null)
    }
  }

  return (
    <div className="mx-auto max-w-3xl space-y-4 px-4 py-6">
      <div>
        <h1 className="text-xl font-semibold text-gray-900 dark:text-gray-100">{t('export.title')}</h1>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t('export.hint')}</p>
      </div>

      {/* ul/li, а не div-ы: это именно список из трёх однородных пунктов, и разметка,
          которая это говорит, читается скринридером как список, а не как три абзаца. */}
      <ul className="divide-y divide-gray-100 rounded-lg border border-gray-200 bg-white dark:divide-gray-700/60 dark:border-gray-700 dark:bg-gray-800">
        <ExportRow
          icon={Table}
          title={t('export.tasksCsv')}
          description={t('export.tasksCsvHint')}
          busy={pending === 'tasks.csv'}
          disabled={!projectId || pending !== null}
          onDownload={() => download('tasks.csv', downloadTasksCsv, `tasks-${projectSlug}.csv`)}
        />
        <ExportRow
          icon={Braces}
          title={t('export.tasksJson')}
          description={t('export.tasksJsonHint')}
          busy={pending === 'tasks.json'}
          disabled={!projectId || pending !== null}
          onDownload={() => download('tasks.json', downloadTasksJson, `tasks-${projectSlug}.json`)}
        />
        <ExportRow
          icon={BookOpen}
          title={t('export.wikiMarkdown')}
          description={wikiIsEmpty ? t('export.wikiEmpty') : t('export.wikiMarkdownHint')}
          busy={pending === 'wiki.md'}
          disabled={!projectId || wikiLoading || wikiIsEmpty || pending !== null}
          onDownload={() => download('wiki.md', downloadWikiMarkdown, `wiki-${projectSlug}.md`)}
        />
      </ul>

      <p className="text-xs text-gray-400 dark:text-gray-500">{t('export.scopeNote')}</p>
    </div>
  )
}

function ExportRow({ icon: Icon, title, description, busy, disabled, onDownload }) {
  const { t } = useTranslation()

  return (
    <li className="flex items-center gap-4 px-4 py-4">
      <Icon className="h-5 w-5 shrink-0 text-gray-400 dark:text-gray-500" aria-hidden="true" />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium text-gray-900 dark:text-gray-100">{title}</p>
        <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">{description}</p>
      </div>
      <button
        type="button"
        onClick={onDownload}
        disabled={disabled}
        className={`${secondaryButtonClass} flex shrink-0 items-center gap-2`}
      >
        <Download className="h-4 w-4" aria-hidden="true" />
        {busy ? t('export.downloading') : t('export.download')}
      </button>
    </li>
  )
}
