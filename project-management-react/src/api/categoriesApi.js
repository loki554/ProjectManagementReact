import { apiClient } from './client'

export function fetchCategories(projectId) {
  return apiClient.get(`/projects/${projectId}/categories`).then((res) => res.data)
}

export function createCategory(projectId, payload) {
  return apiClient.post(`/projects/${projectId}/categories`, payload).then((res) => res.data)
}

export function updateCategory(categoryId, payload) {
  return apiClient.patch(`/categories/${categoryId}`, payload).then((res) => res.data)
}

export function deleteCategory(categoryId) {
  return apiClient.delete(`/categories/${categoryId}`)
}
