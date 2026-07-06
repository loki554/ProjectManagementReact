import { apiClient } from './client'

// List<CommentResponse>: { id, taskId, author (UserSummary), body, createdAt }
export function fetchComments(taskId, sort) {
  return apiClient.get(`/tasks/${taskId}/comments`, { params: { sort } }).then((res) => res.data)
}

export function createComment(taskId, payload) {
  return apiClient.post(`/tasks/${taskId}/comments`, payload).then((res) => res.data)
}

export function deleteComment(commentId) {
  return apiClient.delete(`/comments/${commentId}`)
}
