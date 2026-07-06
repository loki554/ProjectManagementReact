import { zodResolver } from '@hookform/resolvers/zod'
import { useMemo } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { z } from 'zod'
import { useComments, useCreateComment, useDeleteComment } from '../../api/commentsQueries'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { useAuthStore } from '../../stores/authStore'
import { inputClass, primaryButtonClass } from '../ui/FormKit'
import { UserAvatar } from '../ui/UserAvatar'

function buildCommentSchema(t) {
  return z.object({
    body: z.string().min(1, t('auth.validation.required')).max(2000),
  })
}

// canComment — MEMBER и выше (форма добавления); isModerator — ADMIN/OWNER
// (может удалять чужие комментарии; свои может удалять любой автор).
// sort приходит сверху: селект сортировки живёт на строке вкладок в TaskViewPage.
export function TaskCommentsSection({ taskId, projectId, canComment, isModerator, sort }) {
  const { t, i18n } = useTranslation()
  const currentUser = useAuthStore((state) => state.user)

  const { data: comments, isLoading, isError, error } = useComments(taskId, sort)
  const createComment = useCreateComment(taskId, projectId)
  const deleteComment = useDeleteComment(taskId)

  const schema = useMemo(() => buildCommentSchema(t), [i18n.language, t])
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm({ resolver: zodResolver(schema), defaultValues: { body: '' } })

  function onCreate(values) {
    createComment.mutate(values, { onSuccess: () => reset({ body: '' }) })
  }

  function onDelete(commentId) {
    if (!window.confirm(t('tasks.comments.deleteConfirm'))) {
      return
    }
    deleteComment.mutate(commentId)
  }

  const formatDate = (iso) =>
    new Date(iso).toLocaleString(i18n.language, { dateStyle: 'short', timeStyle: 'short' })

  return (
    <div>
      {isLoading && <p className="text-sm text-gray-500">{t('tasks.comments.loading')}</p>}
      {isError && <p className="text-sm text-red-600">{getLocalizedErrorMessage(error, t)}</p>}

      {!isLoading && !isError && comments && (
        <ul className="divide-y divide-gray-100">
          {comments.length === 0 && (
            <li className="py-2 text-sm text-gray-400">{t('tasks.comments.empty')}</li>
          )}
          {comments.map((comment) => {
            const isAuthor = comment.author.id === currentUser?.id
            const canDelete = isAuthor || isModerator
            return (
              <li key={comment.id} className="flex gap-3 py-3">
                <UserAvatar user={comment.author} />
                <div className="min-w-0 flex-1">
                  <div className="flex items-baseline gap-2 text-sm">
                    <span className="font-medium text-gray-900">
                      {comment.author.lastName} {comment.author.firstName}
                    </span>
                    <span className="text-xs text-gray-400">{formatDate(comment.createdAt)}</span>
                  </div>
                  <p className="mt-0.5 text-sm whitespace-pre-wrap text-gray-700">{comment.body}</p>
                </div>
                {canDelete && (
                  <button
                    type="button"
                    onClick={() => onDelete(comment.id)}
                    disabled={deleteComment.isPending}
                    className="shrink-0 text-xs text-red-600 hover:underline disabled:opacity-60"
                  >
                    {t('tasks.comments.delete')}
                  </button>
                )}
              </li>
            )
          })}
        </ul>
      )}
      {deleteComment.isError && (
        <p className="mt-2 text-sm text-red-600">{getLocalizedErrorMessage(deleteComment.error, t)}</p>
      )}

      {canComment && (
        <form onSubmit={handleSubmit(onCreate)} className="mt-3 space-y-2">
          <textarea
            rows={3}
            className={inputClass}
            placeholder={t('tasks.comments.addPlaceholder')}
            {...register('body')}
          />
          {errors.body && <p className="text-xs text-red-600">{errors.body.message}</p>}
          {createComment.isError && (
            <p className="text-sm text-red-600">{getLocalizedErrorMessage(createComment.error, t)}</p>
          )}
          <button type="submit" disabled={createComment.isPending} className={primaryButtonClass}>
            {createComment.isPending ? t('tasks.comments.adding') : t('tasks.comments.add')}
          </button>
        </form>
      )}
    </div>
  )
}
