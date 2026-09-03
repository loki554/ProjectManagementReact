import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import * as invitationsApi from './invitationsApi'

const invitationsKey = (projectId) => ['projects', projectId, 'invitations']
const membersKey = (projectId) => ['projects', projectId, 'members']
const invitationKey = (token) => ['invitation', token]

export function useProjectInvitations(projectId, { enabled = true } = {}) {
  return useQuery({
    queryKey: invitationsKey(projectId),
    queryFn: () => invitationsApi.fetchProjectInvitations(projectId),
    // Список видят только OWNER/ADMIN — остальным запрос вернул бы 403, поэтому страница
    // участников включает его только тем, кому он доступен (см. ProjectMembersPage).
    enabled: Boolean(projectId) && enabled,
  })
}

export function useRevokeInvitation(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (invitationId) => invitationsApi.revokeInvitation(projectId, invitationId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: invitationsKey(projectId) })
    },
  })
}

// Приглашение по токену из ссылки. retry: false — недействительный или просроченный токен
// это 400, а не сбой сети: повторять его три раза значит только задерживать показ
// «ссылка больше не работает».
export function useInvitation(token) {
  return useQuery({
    queryKey: invitationKey(token),
    queryFn: () => invitationsApi.fetchInvitation(token),
    enabled: Boolean(token),
    retry: false,
  })
}

export function useAcceptInvitation(token) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => invitationsApi.acceptInvitation(token),
    onSuccess: (accepted) => {
      // Проект появился в списке пользователя, а сам он — в списке участников: и то, и
      // другое могло быть прочитано этой вкладкой раньше.
      queryClient.invalidateQueries({ queryKey: ['projects'] })
      queryClient.invalidateQueries({ queryKey: membersKey(accepted?.projectId) })
    },
  })
}
