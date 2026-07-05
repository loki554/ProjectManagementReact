import { Pencil } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useParams } from 'react-router-dom'
import { useProjectBySlug } from '../../api/projectsQueries'
import { useProjectWiki, useUpdateWiki } from '../../api/wikiQueries'
import { MarkdownEditor } from '../../components/markdown/MarkdownEditor'
import { MarkdownRenderer } from '../../components/markdown/MarkdownRenderer'
import { primaryButtonClass, secondaryButtonClass } from '../../components/ui/FormKit'
import { roleIsAtLeast } from '../../lib/constants'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'

export function ProjectWikiPage() {
  const { t, i18n } = useTranslation()
  const { projectSlug } = useParams()

  const { data: project } = useProjectBySlug(projectSlug)
  const projectId = project?.id
  const { data: wiki, isLoading, isError, error } = useProjectWiki(projectId)
  const updateWiki = useUpdateWiki(projectId)

  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState('')

  function startEditing() {
    setDraft(wiki?.content ?? '')
    updateWiki.reset()
    setEditing(true)
  }

  function save() {
    updateWiki.mutate(draft, {
      onSuccess: () => setEditing(false),
    })
  }

  // Косметическое скрытие — PUT на бэкенде в любом случае требует MEMBER и выше.
  const canEdit = project && roleIsAtLeast(project.myRole, 'MEMBER')

  return (
    <div className="mx-auto max-w-7xl px-4 py-8">
      <div className="mb-4 flex items-center justify-between gap-3">
        <h1 className="text-xl font-semibold text-gray-900">{t('wiki.title')}</h1>
        {canEdit && !editing && !isLoading && !isError && (
          <button type="button" onClick={startEditing} className={`${secondaryButtonClass} flex items-center gap-2`}>
            <Pencil className="h-4 w-4" aria-hidden="true" />
            {t('wiki.edit')}
          </button>
        )}
      </div>

      {isLoading && <p className="text-gray-500">{t('wiki.loading')}</p>}
      {isError && <p className="text-sm text-red-600">{getLocalizedErrorMessage(error, t)}</p>}

      {!isLoading && !isError && !editing && (
        <div className="rounded-lg border border-gray-200 bg-white p-6">
          {wiki?.content ? (
            <MarkdownRenderer>{wiki.content}</MarkdownRenderer>
          ) : (
            <p className="text-sm text-gray-400">{t('wiki.empty')}</p>
          )}
          {wiki?.updatedAt && (
            <p className="mt-4 border-t border-gray-100 pt-3 text-xs text-gray-400">
              {t('wiki.updatedBy', {
                user: wiki.updatedBy
                  ? `${wiki.updatedBy.lastName} ${wiki.updatedBy.firstName}`
                  : t('wiki.deletedUser'),
                date: new Date(wiki.updatedAt).toLocaleString(i18n.language, {
                  dateStyle: 'short',
                  timeStyle: 'short',
                }),
              })}
            </p>
          )}
        </div>
      )}

      {editing && (
        <div className="space-y-3">
          <MarkdownEditor value={draft} onChange={setDraft} placeholder={t('wiki.placeholder')} />
          {updateWiki.isError && (
            <p className="text-sm text-red-600">{getLocalizedErrorMessage(updateWiki.error, t)}</p>
          )}
          <div className="flex gap-3">
            <button
              type="button"
              onClick={save}
              disabled={updateWiki.isPending}
              className={primaryButtonClass}
            >
              {updateWiki.isPending ? t('wiki.saving') : t('wiki.save')}
            </button>
            <button
              type="button"
              onClick={() => setEditing(false)}
              disabled={updateWiki.isPending}
              className={secondaryButtonClass}
            >
              {t('wiki.cancel')}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
