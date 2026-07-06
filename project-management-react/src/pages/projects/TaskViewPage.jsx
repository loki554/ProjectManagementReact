import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router-dom'
import { z } from 'zod'
import { downloadAttachment } from '../../api/attachmentsApi'
import { useAttachments, useDeleteAttachment, useUploadAttachment } from '../../api/attachmentsQueries'
import { useProjectBySlug, useProjectMembers } from '../../api/projectsQueries'
import { useCreateSubtask, useSubtasks, useTaskByNumber } from '../../api/tasksQueries'
import { useCreateTimeLog, useDeleteTimeLog, useTimeLogs } from '../../api/timeLogsQueries'
import { AttachmentDropzone } from '../../components/attachments/AttachmentDropzone'
import { AttachmentPreviewModal } from '../../components/attachments/AttachmentPreviewModal'
import { AttachmentThumbnail } from '../../components/attachments/AttachmentThumbnail'
import { MarkdownRenderer } from '../../components/markdown/MarkdownRenderer'
import { ActivityFeed } from '../../components/projects/ActivityFeed'
import { TaskCommentsSection } from '../../components/tasks/TaskCommentsSection'
import { Field, inputClass, primaryButtonClass } from '../../components/ui/FormKit'
import { UserAvatar } from '../../components/ui/UserAvatar'
import {
  ATTACHMENT_ACCEPT,
  TASK_NUMBER_BADGE_CLASS,
  roleIsAtLeast,
  taskStatusBadgeClass,
  taskUrgencyBadgeClass,
} from '../../lib/constants'
import { downloadBlob } from '../../lib/downloadBlob'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { formatFileSize } from '../../lib/formatFileSize'
import { tagBadgeStyle } from '../../lib/tagColor'
import { useAuthStore } from '../../stores/authStore'

function buildSubtaskSchema(t) {
  return z.object({
    title: z.string().min(1, t('auth.validation.required')).max(255),
  })
}

function buildTimeLogSchema(t) {
  return z
    .object({
      hours: z.coerce
        .number()
        .gt(0, t('tasks.timeLogs.validation.hoursRange'))
        .lte(24, t('tasks.timeLogs.validation.hoursRange')),
      spentOn: z.string().min(1, t('auth.validation.required')),
      description: z.string().max(1000).optional(),
    })
    .refine((values) => values.spentOn <= new Date().toISOString().slice(0, 10), {
      path: ['spentOn'],
      message: t('tasks.timeLogs.validation.dateNotFuture'),
    })
}

export function TaskViewPage() {
  const { t, i18n } = useTranslation()
  const { projectSlug, taskNumber } = useParams()
  const currentUser = useAuthStore((state) => state.user)

  const { data: project, isLoading: projectLoading } = useProjectBySlug(projectSlug)
  const projectId = project?.id
  const {
    data: task,
    isLoading: taskLoading,
    isError,
    error,
  } = useTaskByNumber(projectId, taskNumber)
  const taskId = task?.id
  const isLoading = projectLoading || taskLoading
  const { data: members } = useProjectMembers(projectId)
  const {
    data: subtasks,
    isLoading: subtasksLoading,
    isError: subtasksIsError,
    error: subtasksError,
  } = useSubtasks(taskId)
  const {
    data: timeLogs,
    isLoading: timeLogsLoading,
    isError: timeLogsIsError,
    error: timeLogsError,
  } = useTimeLogs(taskId)
  const {
    data: attachments,
    isLoading: attachmentsLoading,
    isError: attachmentsIsError,
    error: attachmentsError,
  } = useAttachments(taskId)

  const createSubtask = useCreateSubtask(taskId)
  const createTimeLog = useCreateTimeLog(taskId)
  const deleteTimeLog = useDeleteTimeLog(taskId)
  const uploadAttachment = useUploadAttachment(taskId)
  const deleteAttachmentMutation = useDeleteAttachment(taskId)
  // Скачивание — разовое действие (сохранение файла на диск пользователя), а не
  // кэшируемые данные, поэтому это обычный useMutation прямо на странице, а не
  // экспортируемый хук из attachmentsQueries.js (см. 7.4).
  const downloadAttachmentMutation = useMutation({
    mutationFn: async (attachment) => {
      const blob = await downloadAttachment(attachment.id)
      downloadBlob(blob, attachment.originalFilename)
    },
  })
  const [preview, setPreview] = useState(null)
  const [activeTab, setActiveTab] = useState('comments')
  const [commentsSort, setCommentsSort] = useState('newest')

  const myMembership = members?.find((member) => member.userId === currentUser?.id)
  const canManage = myMembership ? roleIsAtLeast(myMembership.role, 'MEMBER') : false
  const isModerator = myMembership ? roleIsAtLeast(myMembership.role, 'ADMIN') : false

  const subtaskSchema = useMemo(() => buildSubtaskSchema(t), [i18n.language, t])
  const {
    register: registerSubtask,
    handleSubmit: handleSubmitSubtask,
    reset: resetSubtask,
    formState: { errors: subtaskErrors },
  } = useForm({ resolver: zodResolver(subtaskSchema), defaultValues: { title: '' } })

  const todayIso = useMemo(() => new Date().toISOString().slice(0, 10), [])
  const timeLogDefaults = useMemo(() => ({ hours: '', spentOn: todayIso, description: '' }), [todayIso])
  const timeLogSchema = useMemo(() => buildTimeLogSchema(t), [i18n.language, t])
  const {
    register: registerTimeLog,
    handleSubmit: handleSubmitTimeLog,
    reset: resetTimeLog,
    formState: { errors: timeLogErrors },
  } = useForm({ resolver: zodResolver(timeLogSchema), defaultValues: timeLogDefaults })

  function onCreateSubtask(values) {
    createSubtask.mutate(values, { onSuccess: () => resetSubtask({ title: '' }) })
  }

  function onCreateTimeLog(values) {
    createTimeLog.mutate(
      { hours: values.hours, spentOn: values.spentOn, description: values.description || null },
      { onSuccess: () => resetTimeLog(timeLogDefaults) },
    )
  }

  function onDeleteTimeLog(timeLogId) {
    if (!window.confirm(t('tasks.timeLogs.deleteConfirm'))) {
      return
    }
    deleteTimeLog.mutate(timeLogId)
  }

  function onUploadAttachment(file) {
    uploadAttachment.mutate(file)
  }

  function onDeleteAttachment(attachmentId) {
    if (!window.confirm(t('tasks.attachments.deleteConfirm'))) {
      return
    }
    deleteAttachmentMutation.mutate(attachmentId)
  }

  const formatDate = (iso) =>
    new Date(iso).toLocaleString(i18n.language, { dateStyle: 'short', timeStyle: 'short' })

  const tabClass = (tab) =>
    `border-b-2 px-1 pb-2 text-sm font-medium ${
      activeTab === tab
        ? 'border-purple-600 text-purple-700'
        : 'border-transparent text-gray-500 hover:text-gray-700'
    }`

  return (
    <>
      <div className="mx-auto max-w-6xl px-4 py-8">
        {task?.parentTaskId && (
          <Link
            to={`/projects/${projectSlug}/tasks/${task.parentTaskNumber}`}
            className="text-sm text-purple-600 hover:underline"
          >
            {t('tasks.detail.backToParent')}
          </Link>
        )}

        {isLoading && <p className="mt-4 text-gray-500">{t('tasks.detail.loading')}</p>}
        {isError && <p className="mt-4 text-sm text-red-600">{getLocalizedErrorMessage(error, t)}</p>}

        {!isLoading && !isError && task && (
          <div className="mt-4 grid grid-cols-1 gap-6 lg:grid-cols-[minmax(0,1fr)_280px] lg:items-start">
            {/* ── Основная колонка ── */}
            <div className="min-w-0 space-y-6">
              <div className="rounded-lg border border-gray-200 bg-white p-6">
                {/* Без flex-wrap: при длинном названии блок «Создал» должен остаться
                    справа (название переносится, а не выталкивает блок вниз). */}
                <div className="flex items-start justify-between gap-4">
                  <h1 className="flex min-w-0 flex-1 items-start gap-2 text-xl font-semibold text-gray-900">
                    <span className={`${TASK_NUMBER_BADGE_CLASS} mt-1`}>#{task.taskNumber}</span>
                    <span className="min-w-0 break-words">{task.title}</span>
                  </h1>
                  <div className="flex shrink-0 items-center gap-2 text-sm text-gray-500">
                    <div className="text-right">
                      <div>
                        {t('tasks.detail.createdBy')}{' '}
                        <span className="font-medium text-gray-700">
                          {task.createdBy.lastName} {task.createdBy.firstName}
                        </span>
                      </div>
                      <div className="text-xs">{formatDate(task.createdAt)}</div>
                    </div>
                    <UserAvatar user={task.createdBy} />
                  </div>
                </div>

                <div className="mt-4 border-t border-gray-100 pt-4">
                  {task.description ? (
                    <MarkdownRenderer>{task.description}</MarkdownRenderer>
                  ) : (
                    <p className="text-sm text-gray-400">{t('tasks.detail.noDescription')}</p>
                  )}
                </div>
              </div>

              <div className="rounded-lg border border-gray-200 bg-white p-6">
                <h2 className="mb-3 text-sm font-semibold text-gray-900">{t('tasks.subtasks.title')}</h2>

                {subtasksLoading && <p className="text-sm text-gray-500">{t('tasks.subtasks.loading')}</p>}
                {subtasksIsError && (
                  <p className="text-sm text-red-600">{getLocalizedErrorMessage(subtasksError, t)}</p>
                )}

                {!subtasksLoading && !subtasksIsError && subtasks && (
                  <ul className="mb-4 divide-y divide-gray-100">
                    {subtasks.length === 0 && (
                      <li className="py-2 text-sm text-gray-400">{t('tasks.subtasks.empty')}</li>
                    )}
                    {subtasks.map((subtask) => (
                      <li key={subtask.id}>
                        <Link
                          to={`/projects/${projectSlug}/tasks/${subtask.taskNumber}`}
                          className="flex items-center justify-between gap-3 py-2 text-sm hover:text-purple-700"
                        >
                          <span className="flex min-w-0 items-center gap-2 text-gray-900">
                            <span className={TASK_NUMBER_BADGE_CLASS}>#{subtask.taskNumber}</span>
                            <span className="truncate">{subtask.title}</span>
                          </span>
                          <span
                            className={`rounded-full px-2 py-0.5 text-xs font-medium ${taskStatusBadgeClass(subtask.status)}`}
                          >
                            {t(`tasks.status.${subtask.status}`)}
                          </span>
                        </Link>
                      </li>
                    ))}
                  </ul>
                )}

                {canManage && (
                  <form onSubmit={handleSubmitSubtask(onCreateSubtask)} className="flex flex-wrap items-start gap-3">
                    <div className="min-w-48 flex-1">
                      <input
                        type="text"
                        className={inputClass}
                        placeholder={t('tasks.subtasks.addPlaceholder')}
                        {...registerSubtask('title')}
                      />
                      {subtaskErrors.title && (
                        <p className="mt-1 text-xs text-red-600">{subtaskErrors.title.message}</p>
                      )}
                    </div>
                    <button type="submit" disabled={createSubtask.isPending} className={primaryButtonClass}>
                      {createSubtask.isPending ? t('tasks.subtasks.adding') : t('tasks.subtasks.add')}
                    </button>
                  </form>
                )}
                {createSubtask.isError && (
                  <p className="mt-2 text-sm text-red-600">{getLocalizedErrorMessage(createSubtask.error, t)}</p>
                )}
              </div>

              <div className="rounded-lg border border-gray-200 bg-white p-6">
                <h2 className="mb-3 text-sm font-semibold text-gray-900">{t('tasks.attachments.title')}</h2>

                {attachmentsLoading && <p className="text-sm text-gray-500">{t('tasks.attachments.loading')}</p>}
                {attachmentsIsError && (
                  <p className="text-sm text-red-600">{getLocalizedErrorMessage(attachmentsError, t)}</p>
                )}

                {!attachmentsLoading && !attachmentsIsError && attachments && (
                  <ul className="mb-4 divide-y divide-gray-100">
                    {attachments.length === 0 && (
                      <li className="py-2 text-sm text-gray-400">{t('tasks.attachments.empty')}</li>
                    )}
                    {attachments.map((attachment) => {
                      const isUploader = attachment.uploadedBy.id === currentUser?.id
                      const canDeleteAttachment = canManage && (isUploader || isModerator)
                      return (
                        <li key={attachment.id} className="flex items-center gap-3 py-2 text-sm">
                          <AttachmentThumbnail
                            attachment={attachment}
                            onPreview={(url) => setPreview({ url, filename: attachment.originalFilename })}
                          />
                          <div className="min-w-0 flex-1">
                            <button
                              type="button"
                              onClick={() => downloadAttachmentMutation.mutate(attachment)}
                              disabled={downloadAttachmentMutation.isPending}
                              className="block max-w-full truncate text-left text-purple-700 hover:underline disabled:opacity-60"
                            >
                              {attachment.originalFilename}
                            </button>
                            <div className="text-xs text-gray-500">
                              {formatFileSize(attachment.sizeBytes)} · {attachment.uploadedBy.lastName}{' '}
                              {attachment.uploadedBy.firstName} ·{' '}
                              {new Date(attachment.createdAt).toLocaleDateString(i18n.language)}
                            </div>
                          </div>
                          {canDeleteAttachment && (
                            <button
                              type="button"
                              onClick={() => onDeleteAttachment(attachment.id)}
                              disabled={deleteAttachmentMutation.isPending}
                              className="shrink-0 text-xs text-red-600 hover:underline disabled:opacity-60"
                            >
                              {t('tasks.attachments.delete')}
                            </button>
                          )}
                        </li>
                      )
                    })}
                  </ul>
                )}

                {canManage && (
                  <AttachmentDropzone
                    onFileSelected={onUploadAttachment}
                    disabled={uploadAttachment.isPending}
                    pending={uploadAttachment.isPending}
                    accept={ATTACHMENT_ACCEPT}
                    hintText={t('tasks.attachments.dropHint')}
                    activeText={t('tasks.attachments.uploading')}
                  />
                )}
                {uploadAttachment.isError && (
                  <p className="mt-2 text-sm text-red-600">{getLocalizedErrorMessage(uploadAttachment.error, t)}</p>
                )}
                {deleteAttachmentMutation.isError && (
                  <p className="mt-2 text-sm text-red-600">
                    {getLocalizedErrorMessage(deleteAttachmentMutation.error, t)}
                  </p>
                )}
                {downloadAttachmentMutation.isError && (
                  <p className="mt-2 text-sm text-red-600">
                    {getLocalizedErrorMessage(downloadAttachmentMutation.error, t)}
                  </p>
                )}
              </div>

              <div className="rounded-lg border border-gray-200 bg-white p-6">
                <div className="mb-3 flex items-center justify-between">
                  <h2 className="text-sm font-semibold text-gray-900">{t('tasks.timeLogs.title')}</h2>
                  {timeLogs && (
                    <span className="text-sm text-gray-600">
                      {t('tasks.timeLogs.totalLabel', { hours: Number(timeLogs.totalHours).toFixed(2) })}
                    </span>
                  )}
                </div>

                {timeLogsLoading && <p className="text-sm text-gray-500">{t('tasks.timeLogs.loading')}</p>}
                {timeLogsIsError && (
                  <p className="text-sm text-red-600">{getLocalizedErrorMessage(timeLogsError, t)}</p>
                )}

                {!timeLogsLoading && !timeLogsIsError && timeLogs && (
                  <ul className="mb-4 divide-y divide-gray-100">
                    {timeLogs.items.length === 0 && (
                      <li className="py-2 text-sm text-gray-400">{t('tasks.timeLogs.empty')}</li>
                    )}
                    {timeLogs.items.map((entry) => {
                      const isAuthor = entry.user.id === currentUser?.id
                      const canDelete = canManage && (isAuthor || isModerator)
                      return (
                        <li key={entry.id} className="flex items-start justify-between gap-3 py-2 text-sm">
                          <div>
                            <div className="text-gray-900">
                              {entry.spentOn} · {Number(entry.hours).toFixed(2)} {t('tasks.timeLogs.hoursShort')} ·{' '}
                              {entry.user.lastName} {entry.user.firstName}
                            </div>
                            {entry.description && <div className="text-gray-500">{entry.description}</div>}
                          </div>
                          {canDelete && (
                            <button
                              type="button"
                              onClick={() => onDeleteTimeLog(entry.id)}
                              disabled={deleteTimeLog.isPending}
                              className="shrink-0 text-xs text-red-600 hover:underline disabled:opacity-60"
                            >
                              {t('tasks.timeLogs.delete')}
                            </button>
                          )}
                        </li>
                      )
                    })}
                  </ul>
                )}

                {canManage && (
                  <form onSubmit={handleSubmitTimeLog(onCreateTimeLog)} className="space-y-3">
                    <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
                      <Field label={t('tasks.timeLogs.hoursLabel')} error={timeLogErrors.hours?.message}>
                        <input type="number" step="any" className={inputClass} {...registerTimeLog('hours')} />
                      </Field>
                      <Field label={t('tasks.timeLogs.dateLabel')} error={timeLogErrors.spentOn?.message}>
                        <input type="date" className={inputClass} {...registerTimeLog('spentOn')} />
                      </Field>
                      <Field label={t('tasks.timeLogs.descriptionLabel')}>
                        <input type="text" className={inputClass} {...registerTimeLog('description')} />
                      </Field>
                    </div>
                    <button type="submit" disabled={createTimeLog.isPending} className={primaryButtonClass}>
                      {createTimeLog.isPending ? t('tasks.timeLogs.adding') : t('tasks.timeLogs.add')}
                    </button>
                  </form>
                )}
                {createTimeLog.isError && (
                  <p className="mt-2 text-sm text-red-600">{getLocalizedErrorMessage(createTimeLog.error, t)}</p>
                )}
                {deleteTimeLog.isError && (
                  <p className="mt-2 text-sm text-red-600">{getLocalizedErrorMessage(deleteTimeLog.error, t)}</p>
                )}
              </div>

              <div className="rounded-lg border border-gray-200 bg-white p-6">
                <div className="flex items-center justify-between gap-3 border-b border-gray-200">
                  <div className="flex gap-6">
                    <button type="button" onClick={() => setActiveTab('comments')} className={tabClass('comments')}>
                      {t('tasks.detail.tabs.comments')}
                    </button>
                    <button type="button" onClick={() => setActiveTab('activity')} className={tabClass('activity')}>
                      {t('tasks.detail.tabs.activity')}
                    </button>
                  </div>
                  {/* Селект живёт на строке вкладок (справа), а не внутри списка комментариев —
                      поэтому состояние сортировки поднято сюда из TaskCommentsSection. */}
                  {activeTab === 'comments' && (
                    <select
                      value={commentsSort}
                      onChange={(event) => setCommentsSort(event.target.value)}
                      aria-label={t('tasks.comments.sortLabel')}
                      className="mb-2 rounded-md border border-gray-300 bg-white px-2 py-1 text-xs text-gray-700 focus:border-purple-500 focus:outline-none"
                    >
                      <option value="newest">{t('tasks.comments.sortNewest')}</option>
                      <option value="oldest">{t('tasks.comments.sortOldest')}</option>
                    </select>
                  )}
                </div>
                <div className="pt-4">
                  {activeTab === 'comments' ? (
                    <TaskCommentsSection
                      taskId={taskId}
                      projectId={projectId}
                      canComment={canManage}
                      isModerator={isModerator}
                      sort={commentsSort}
                    />
                  ) : (
                    <ActivityFeed projectId={projectId} projectSlug={projectSlug} taskId={taskId} embedded />
                  )}
                </div>
              </div>
            </div>

            {/* ── Правая панель ── */}
            <aside className="space-y-4 rounded-lg border border-gray-200 bg-white p-6 lg:sticky lg:top-6">
              <div
                className={`rounded-full px-3 py-1.5 text-center text-sm font-semibold ${taskStatusBadgeClass(task.status)}`}
              >
                {t(`tasks.status.${task.status}`)}
              </div>

              {canManage && (
                <Link
                  to={`/projects/${projectSlug}/tasks/${taskNumber}/edit`}
                  className={`${primaryButtonClass} block text-center`}
                >
                  {t('tasks.detail.edit')}
                </Link>
              )}

              <dl className="space-y-3 border-t border-gray-100 pt-4 text-sm">
                <div>
                  <dt className="text-xs font-medium text-gray-500">{t('tasks.detail.tagLabel')}</dt>
                  <dd className="mt-1">
                    {task.tag ? (
                      <span
                        className="rounded-full px-2 py-0.5 text-xs font-medium"
                        style={tagBadgeStyle(task.tag.color)}
                      >
                        {task.tag.name}
                      </span>
                    ) : (
                      <span className="text-gray-400">{t('tasks.noTag')}</span>
                    )}
                  </dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-gray-500">{t('tasks.detail.urgencyLabel')}</dt>
                  <dd className="mt-1">
                    <span
                      className={`rounded-full px-2 py-0.5 text-xs font-medium ${taskUrgencyBadgeClass(task.urgency)}`}
                    >
                      {t(`urgency.${task.urgency}`)}
                    </span>
                  </dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-gray-500">{t('tasks.detail.dueDateLabel')}</dt>
                  <dd className="mt-1 text-gray-900">
                    {task.dueDate ? formatDate(task.dueDate) : <span className="text-gray-400">—</span>}
                  </dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-gray-500">{t('tasks.detail.hoursSpentLabel')}</dt>
                  <dd className="mt-1 text-gray-900">
                    {Number(task.totalHoursSpent).toFixed(2)} {t('tasks.timeLogs.hoursShort')}
                  </dd>
                </div>
              </dl>

              <div className="border-t border-gray-100 pt-4">
                <div className="text-xs font-medium text-gray-500">{t('tasks.detail.assigneeLabel')}</div>
                <div className="mt-2 flex items-center gap-2 text-sm text-gray-900">
                  {task.assignee ? (
                    <>
                      <UserAvatar user={task.assignee} />
                      <span className="min-w-0 truncate">
                        {task.assignee.lastName} {task.assignee.firstName}
                      </span>
                    </>
                  ) : (
                    <span className="text-gray-400">{t('tasks.unassigned')}</span>
                  )}
                </div>
              </div>
            </aside>
          </div>
        )}
      </div>

      {preview && (
        <AttachmentPreviewModal
          imageUrl={preview.url}
          filename={preview.filename}
          onClose={() => setPreview(null)}
        />
      )}
    </>
  )
}
