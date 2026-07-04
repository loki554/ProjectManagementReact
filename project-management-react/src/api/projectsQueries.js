import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import * as projectsApi from './projectsApi'

const projectsKey = ['projects']
const projectKey = (projectId) => ['projects', projectId]
const membersKey = (projectId) => ['projects', projectId, 'members']

export function useProjects() {
  return useQuery({
    queryKey: projectsKey,
    queryFn: projectsApi.fetchProjects,
  })
}

export function useProject(projectId) {
  return useQuery({
    queryKey: projectKey(projectId),
    queryFn: () => projectsApi.fetchProject(projectId),
    enabled: Boolean(projectId),
  })
}

// Разрешает slug (или, для обратной совместимости, сырой UUID) из URL в полный проект —
// используется страницами, зашедшими по читаемому пути /projects/:projectSlug/...
export function useProjectBySlug(slug) {
  return useQuery({
    queryKey: ['projects', 'by-slug', slug],
    queryFn: () => projectsApi.fetchProjectBySlug(slug),
    enabled: Boolean(slug),
  })
}

export function useCreateProject() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: projectsApi.createProject,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: projectsKey })
    },
  })
}

// projectId фиксируется на уровне хука (не аргументом mutate) — предполагается использование
// со страницы настроек одного конкретного проекта, а не списка нескольких сразу.
export function useUpdateProject(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload) => projectsApi.updateProject(projectId, payload),
    onSuccess: (data) => {
      queryClient.setQueryData(projectKey(projectId), data)
      queryClient.invalidateQueries({ queryKey: projectsKey })
      // ProjectLayout/Overview живут на ключе by-slug — без инвалидации сайдбар
      // показывал бы устаревшие имя/описание до перезагрузки страницы.
      queryClient.invalidateQueries({ queryKey: ['projects', 'by-slug'] })
    },
  })
}

// В отличие от useUpdateProject, projectId здесь — аргумент mutate: хук предполагается
// использованным один раз на страницу со списком нескольких проектов (кнопка удаления в строке).
export function useDeleteProject() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (projectId) => projectsApi.deleteProject(projectId),
    onSuccess: (_data, projectId) => {
      queryClient.invalidateQueries({ queryKey: projectsKey })
      queryClient.removeQueries({ queryKey: projectKey(projectId) })
    },
  })
}

// projectId — аргумент mutate (не зафиксирован на хуке), т.к. вызывается сразу после
// useCreateProject, когда id проекта известен только в момент onSuccess создания.
export function useUploadProjectPreviewImage() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ projectId, file }) => projectsApi.uploadProjectPreviewImage(projectId, file),
    onSuccess: (data, { projectId }) => {
      queryClient.setQueryData(projectKey(projectId), data)
      queryClient.invalidateQueries({ queryKey: projectsKey })
      // См. useUpdateProject: сайдбар/обзор читают проект по ключу by-slug.
      queryClient.invalidateQueries({ queryKey: ['projects', 'by-slug'] })
    },
  })
}

export function useProjectMembers(projectId) {
  return useQuery({
    queryKey: membersKey(projectId),
    queryFn: () => projectsApi.fetchProjectMembers(projectId),
    enabled: Boolean(projectId),
  })
}

export function useInviteMember(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload) => projectsApi.inviteMember(projectId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: membersKey(projectId) })
    },
  })
}

export function useUpdateMemberRole(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ userId, role }) => projectsApi.updateMemberRole(projectId, userId, role),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: membersKey(projectId) })
    },
  })
}

export function useRemoveMember(projectId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (userId) => projectsApi.removeMember(projectId, userId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: membersKey(projectId) })
    },
  })
}
