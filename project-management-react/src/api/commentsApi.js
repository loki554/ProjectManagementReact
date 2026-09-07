import { apiClient } from './client'

// List<CommentResponse>: { id, taskId, author (UserSummary), body, createdAt, editedAt }
// editedAt = null — комментарий не редактировали (4.4).
export function fetchComments(taskId, sort) {
  return apiClient.get(`/tasks/${taskId}/comments`, { params: { sort } }).then((res) => res.data)
}

export function createComment(taskId, payload) {
  return apiClient.post(`/tasks/${taskId}/comments`, payload).then((res) => res.data)
}

// Правка комментария (4.4). Автор и только он — модератор чужой текст не правит,
// на попытку прилетит 403 NOT_COMMENT_AUTHOR.
export function updateComment(commentId, payload) {
  return apiClient.patch(`/comments/${commentId}`, payload).then((res) => res.data)
}

export function deleteComment(commentId) {
  return apiClient.delete(`/comments/${commentId}`)
}
