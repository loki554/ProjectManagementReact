import { zodResolver } from '@hookform/resolvers/zod'
import { useMemo, useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { z } from 'zod'
import { useComments, useCreateComment, useDeleteComment, useUpdateComment } from '../../api/commentsQueries'
import { confirmAction } from '../../stores/confirmStore'
import { useProjectMembers } from '../../api/projectsQueries'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { useAuthStore } from '../../stores/authStore'
import { primaryButtonClass } from '../ui/FormKit'
import { MentionTextarea } from '../ui/MentionTextarea'
import { UserAvatar } from '../ui/UserAvatar'
import { CommentBody } from './CommentBody'

const COMMENT_MAX_LENGTH = 2000

function buildCommentSchema(t) {
  return z.object({
    body: z.string().min(1, t('auth.validation.required')).max(COMMENT_MAX_LENGTH),
  })
}

// canComment — MEMBER и выше (форма добавления); isModerator — ADMIN/OWNER
// (может удалять чужие комментарии; свои может удалять любой автор).
// sort приходит сверху: селект сортировки живёт на строке вкладок в TaskViewPage.
//
// Править (4.4) может только автор — и только свой текст, в том числе мимо этого списка:
// проверку делает бэкенд (NOT_COMMENT_AUTHOR), кнопка лишь не показывается там, где она
// заведомо ни к чему.
export function TaskCommentsSection({ taskId, projectId, canComment, isModerator, sort }) {
  const { t, i18n } = useTranslation()
  const currentUser = useAuthStore((state) => state.user)

  const { data: comments, isLoading, isError, error } = useComments(taskId, sort)
  const createComment = useCreateComment(taskId, projectId)
  const updateComment = useUpdateComment(taskId)
  const deleteComment = useDeleteComment(taskId)

  // Участники нужны в обе стороны: автокомплиту @упоминаний — как список подсказок,
  // показу комментариев — чтобы отличить упоминание участника от похожего текста. Запрос
  // тот же самый, что у вкладки участников проекта, поэтому обычно уже в кэше.
  const { data: members } = useProjectMembers(projectId)
  const membersByUsername = useMemo(
    () => new Map((members ?? []).map((member) => [member.username.toLowerCase(), member])),
    [members],
  )

  // id редактируемого комментария и черновик его текста. Один на весь список: две
  // одновременно открытые формы правки — состояние, которого человек не выбирал.
  const [editing, setEditing] = useState(null)

  const schema = useMemo(() => buildCommentSchema(t), [i18n.language, t])
  const {
    control,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm({ resolver: zodResolver(schema), defaultValues: { body: '' } })

  function onCreate(values) {
    createComment.mutate(values, { onSuccess: () => reset({ body: '' }) })
  }

  function onSaveEdit() {
    const body = editing.body.trim()
    if (!body || body === editing.originalBody) {
      setEditing(null)
      return
    }
    updateComment.mutate({ commentId: editing.id, body }, { onSuccess: () => setEditing(null) })
  }

  async function onDelete(commentId) {
    if (!(await confirmAction({ title: t('tasks.comments.deleteConfirm'), confirmLabel: t('confirm.delete') }))) {
      return
    }
    deleteComment.mutate(commentId)
  }

  const formatDate = (iso) =>
    new Date(iso).toLocaleString(i18n.language, { dateStyle: 'short', timeStyle: 'short' })

  return (
    <div>
      {isLoading && <p className="text-sm text-gray-500 dark:text-gray-400">{t('tasks.comments.loading')}</p>}
      {isError && <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(error, t)}</p>}

      {!isLoading && !isError && comments && (
        <ul className="divide-y divide-gray-100 dark:divide-gray-700">
          {comments.length === 0 && (
            <li className="py-2 text-sm text-gray-400 dark:text-gray-500">{t('tasks.comments.empty')}</li>
          )}
          {comments.map((comment) => {
            const isAuthor = comment.author.id === currentUser?.id
            const canDelete = isAuthor || isModerator
            const canEdit = isAuthor && canComment
            const isEditing = editing?.id === comment.id
            return (
              <li key={comment.id} className="flex gap-3 py-3">
                <UserAvatar user={comment.author} />
                <div className="min-w-0 flex-1">
                  <div className="flex items-baseline gap-2 text-sm">
                    <span className="font-medium text-gray-900 dark:text-gray-100">
                      {comment.author.lastName} {comment.author.firstName}
                    </span>
                    <span className="text-xs text-gray-400 dark:text-gray-500">{formatDate(comment.createdAt)}</span>
                    {/* Пометка правки — с точным временем в подсказке: сам факт важен всем,
                        читающим тред, а «когда именно» нужен редко и место занимать не должен. */}
                    {comment.editedAt && (
                      <span
                        title={t('tasks.comments.editedAt', { date: formatDate(comment.editedAt) })}
                        className="text-xs text-gray-400 dark:text-gray-500"
                      >
                        {t('tasks.comments.edited')}
                      </span>
                    )}
                  </div>

                  {isEditing ? (
                    <div className="mt-2 space-y-2">
                      <MentionTextarea
                        value={editing.body}
                        onChange={(body) => setEditing((current) => ({ ...current, body }))}
                        members={members}
                        rows={3}
                        autoFocus
                        maxLength={COMMENT_MAX_LENGTH}
                        placeholder={t('tasks.comments.addPlaceholder')}
                      />
                      <div className="flex gap-2">
                        <button
                          type="button"
                          onClick={onSaveEdit}
                          disabled={updateComment.isPending}
                          className={primaryButtonClass}
                        >
                          {updateComment.isPending ? t('tasks.comments.saving') : t('tasks.comments.save')}
                        </button>
                        <button
                          type="button"
                          onClick={() => setEditing(null)}
                          className="rounded-md px-3 py-2 text-sm text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-700"
                        >
                          {t('tasks.comments.cancelEdit')}
                        </button>
                      </div>
                      {updateComment.isError && (
                        <p className="text-sm text-red-600 dark:text-red-400">
                          {getLocalizedErrorMessage(updateComment.error, t)}
                        </p>
                      )}
                    </div>
                  ) : (
                    <CommentBody
                      body={comment.body}
                      membersByUsername={membersByUsername}
                      currentUserId={currentUser?.id}
                    />
                  )}
                </div>

                {!isEditing && (canEdit || canDelete) && (
                  <div className="flex shrink-0 gap-3 text-xs">
                    {canEdit && (
                      <button
                        type="button"
                        onClick={() => setEditing({ id: comment.id, body: comment.body, originalBody: comment.body })}
                        className="text-gray-500 hover:underline dark:text-gray-400"
                      >
                        {t('tasks.comments.edit')}
                      </button>
                    )}
                    {canDelete && (
                      <button
                        type="button"
                        onClick={() => onDelete(comment.id)}
                        disabled={deleteComment.isPending}
                        className="text-red-600 hover:underline disabled:opacity-60 dark:text-red-400"
                      >
                        {t('tasks.comments.delete')}
                      </button>
                    )}
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      )}
      {deleteComment.isError && (
        <p className="mt-2 text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(deleteComment.error, t)}</p>
      )}

      {canComment && (
        <form onSubmit={handleSubmit(onCreate)} className="mt-3 space-y-2">
          {/* Controller, а не register: MentionTextarea управляет и текстом, и кареткой,
              и отдаёт наверх строку, а не событие. */}
          <Controller
            name="body"
            control={control}
            render={({ field }) => (
              <MentionTextarea
                value={field.value}
                onChange={field.onChange}
                members={members}
                rows={3}
                maxLength={COMMENT_MAX_LENGTH}
                placeholder={t('tasks.comments.addPlaceholder')}
              />
            )}
          />
          <p className="text-xs text-gray-400 dark:text-gray-500">{t('tasks.comments.mentionHint')}</p>
          {errors.body && <p className="text-xs text-red-600 dark:text-red-400">{errors.body.message}</p>}
          {createComment.isError && (
            <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(createComment.error, t)}</p>
          )}
          <button type="submit" disabled={createComment.isPending} className={primaryButtonClass}>
            {createComment.isPending ? t('tasks.comments.adding') : t('tasks.comments.add')}
          </button>
        </form>
      )}
    </div>
  )
}
