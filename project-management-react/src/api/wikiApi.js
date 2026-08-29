import { apiClient } from './client'

export function fetchProjectWiki(projectId) {
  return apiClient.get(`/projects/${projectId}/wiki`).then((res) => res.data)
}

// version — та, что пришла с GET (3.4): 409 CONCURRENT_MODIFICATION вместо тихого
// затирания чужой правки. У проекта без вики строки в БД ещё нет, и GET отдаёт версию 0.
export function updateProjectWiki(projectId, { content, version }) {
  return apiClient.put(`/projects/${projectId}/wiki`, { content, version }).then((res) => res.data)
}
