import { zodResolver } from '@hookform/resolvers/zod'
import { useMemo } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { useParams } from 'react-router-dom'
import { z } from 'zod'
import {
  useInviteMember,
  useProjectBySlug,
  useProjectMembers,
  useRemoveMember,
  useUpdateMemberRole,
} from '../../api/projectsQueries'
import { Field, inputClass, primaryButtonClass } from '../../components/ui/FormKit'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { PROJECT_ROLES, roleIsAtLeast } from '../../lib/constants'
import { useAuthStore } from '../../stores/authStore'

function buildInviteSchema(t) {
  return z.object({
    email: z.string().min(1, t('auth.validation.required')).email(t('auth.validation.invalidEmail')),
    role: z.enum(PROJECT_ROLES),
  })
}

export function ProjectMembersPage() {
  const { t, i18n } = useTranslation()
  const { projectSlug } = useParams()
  const currentUser = useAuthStore((state) => state.user)

  const { data: project } = useProjectBySlug(projectSlug)
  const projectId = project?.id
  const { data: members, isLoading, isError, error } = useProjectMembers(projectId)
  const inviteMember = useInviteMember(projectId)
  const updateMemberRole = useUpdateMemberRole(projectId)
  const removeMember = useRemoveMember(projectId)

  // Косметическое скрытие (см. 3.6.5) — сервер всё равно проверяет права на каждом
  // write-эндпоинте (INSUFFICIENT_ROLE), это не единственная линия защиты.
  const myMembership = members?.find((member) => member.userId === currentUser?.id)
  const canManage = myMembership ? roleIsAtLeast(myMembership.role, 'ADMIN') : false

  const schema = useMemo(() => buildInviteSchema(t), [i18n.language, t])

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm({ resolver: zodResolver(schema), defaultValues: { email: '', role: 'MEMBER' } })

  function onInvite(values) {
    inviteMember.mutate(values, { onSuccess: () => reset({ email: '', role: 'MEMBER' }) })
  }

  return (
      <div className="mx-auto max-w-2xl px-4 py-8">
        <h1 className="mb-6 text-2xl font-semibold text-gray-900 dark:text-gray-100">{t('projects.members')}</h1>

        {canManage && (
          <form
            onSubmit={handleSubmit(onInvite)}
            className="mb-6 flex flex-wrap items-end gap-3 rounded-lg border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-800"
          >
            <div className="min-w-48 flex-1">
              <Field label={t('members.email')} error={errors.email?.message}>
                <input type="email" className={inputClass} {...register('email')} />
              </Field>
            </div>
            <div>
              <Field label={t('members.role')}>
                <select className={inputClass} {...register('role')}>
                  {PROJECT_ROLES.map((role) => (
                    <option key={role} value={role}>
                      {t(`roles.${role}`)}
                    </option>
                  ))}
                </select>
              </Field>
            </div>
            <button type="submit" disabled={inviteMember.isPending} className={primaryButtonClass}>
              {inviteMember.isPending ? t('members.inviting') : t('members.invite')}
            </button>
          </form>
        )}

        {inviteMember.isError && (
          <p className="mb-4 text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(inviteMember.error, t)}</p>
        )}
        {updateMemberRole.isError && (
          <p className="mb-4 text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(updateMemberRole.error, t)}</p>
        )}
        {removeMember.isError && (
          <p className="mb-4 text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(removeMember.error, t)}</p>
        )}

        {isLoading && <p className="text-gray-500 dark:text-gray-400">{t('members.loading')}</p>}
        {isError && <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(error, t)}</p>}

        {!isLoading && !isError && members && (
          <ul className="divide-y divide-gray-200 rounded-lg border border-gray-200 bg-white dark:divide-gray-700 dark:border-gray-700 dark:bg-gray-800">
            {members.map((member) => (
              <li key={member.userId} className="flex items-center justify-between gap-4 px-4 py-3">
                <div>
                  <p className="text-sm font-medium text-gray-900 dark:text-gray-100">
                    {member.lastName} {member.firstName}
                  </p>
                  <p className="text-xs text-gray-500 dark:text-gray-400">{member.email}</p>
                </div>

                <div className="flex items-center gap-3">
                  {canManage ? (
                    <select
                      className="rounded-md border border-gray-300 px-2 py-1 text-sm dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100"
                      value={member.role}
                      onChange={(event) =>
                        updateMemberRole.mutate({ userId: member.userId, role: event.target.value })
                      }
                      disabled={updateMemberRole.isPending}
                    >
                      {PROJECT_ROLES.map((role) => (
                        <option key={role} value={role}>
                          {t(`roles.${role}`)}
                        </option>
                      ))}
                    </select>
                  ) : (
                    <span className="rounded-full bg-purple-100 px-2 py-0.5 text-xs font-medium text-purple-700 dark:bg-purple-900/50 dark:text-purple-300">
                      {t(`roles.${member.role}`)}
                    </span>
                  )}

                  {canManage && (
                    <button
                      type="button"
                      onClick={() => removeMember.mutate(member.userId)}
                      disabled={removeMember.isPending}
                      className="text-xs text-red-600 hover:underline disabled:opacity-60 dark:text-red-400"
                    >
                      {t('members.remove')}
                    </button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
  )
}
