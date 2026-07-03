import { apiClient } from './client'

export function fetchProfile() {
  return apiClient.get('/users/me').then((res) => res.data)
}

export function updateProfile(payload) {
  return apiClient.patch('/users/me', payload).then((res) => res.data)
}

export function uploadAvatar(file) {
  const formData = new FormData()
  formData.append('file', file)
  // Content-Type тут намеренно не выставляем — axios сам поставит multipart/form-data
  // с правильным boundary при виде FormData; вручную легко сломать парсинг на бэке.
  return apiClient.post('/users/me/avatar', formData).then((res) => res.data)
}
