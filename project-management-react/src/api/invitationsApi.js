import { apiClient } from './client'

// Приглашение по email того, у кого аккаунта ещё нет (4.2).
//
// Выписывается тем же запросом, что и добавление участника (POST .../members, см.
// projectsApi.inviteMember): приглашающий не знает, зарегистрирован ли адресат, — за него
// это решает сервер и сообщает в поле status ответа (MEMBER_ADDED / INVITATION_SENT).

// Непринятые приглашения проекта. Только OWNER/ADMIN — в отличие от списка участников,
// доступного всем: здесь адреса людей, которые в проект ещё не вошли.
export function fetchProjectInvitations(projectId) {
  return apiClient.get(`/projects/${projectId}/invitations`).then((res) => res.data)
}

export function revokeInvitation(projectId, invitationId) {
  return apiClient.delete(`/projects/${projectId}/invitations/${invitationId}`)
}

// Публичный эндпоинт: страницу приглашения открывают до входа, а часто и до того, как
// аккаунт вообще заведён. Отдаёт только то, что и так написано в письме — название проекта,
// роль, адрес и имя пригласившего.
export function fetchInvitation(token) {
  return apiClient.get(`/invitations/${token}`).then((res) => res.data)
}

// Требует входа: приглашение принимает конкретный аккаунт, и его адрес должен совпадать
// с адресом приглашения (иначе 403 INVITATION_EMAIL_MISMATCH).
export function acceptInvitation(token) {
  return apiClient.post(`/invitations/${token}/accept`).then((res) => res.data)
}
