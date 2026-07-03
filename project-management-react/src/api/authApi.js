import { apiClient } from './client'

export function register(payload) {
  return apiClient.post('/auth/register', payload).then((res) => res.data)
}

export function verifyEmail(token) {
  return apiClient.post('/auth/verify-email', { token }).then((res) => res.data)
}

export function resendVerification(email) {
  return apiClient.post('/auth/resend-verification', { email }).then((res) => res.data)
}

export function login(email, password) {
  return apiClient.post('/auth/login', { email, password }).then((res) => res.data)
}

export function logout(refreshToken) {
  return apiClient.post('/auth/logout', { refreshToken })
}

export function fetchMe() {
  return apiClient.get('/auth/me').then((res) => res.data)
}
