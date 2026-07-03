import { apiClient } from './client'

export function fetchProfile() {
  return apiClient.get('/users/me').then((res) => res.data)
}

export function updateProfile(payload) {
  return apiClient.patch('/users/me', payload).then((res) => res.data)
}
