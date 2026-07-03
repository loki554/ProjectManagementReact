import { useTranslation } from 'react-i18next'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { LanguageSwitcher } from './components/LanguageSwitcher'
import { ProtectedRoute } from './components/ProtectedRoute'
import { DashboardPage } from './pages/DashboardPage'
import { ProfilePage } from './pages/ProfilePage'
import { LoginPage } from './pages/auth/LoginPage'
import { RegisterPage } from './pages/auth/RegisterPage'
import { VerifyEmailPage } from './pages/auth/VerifyEmailPage'
import { useAuthBootstrap } from './stores/useAuthBootstrap'

function App() {
  const { t } = useTranslation()
  // Пока идёт попытка молча восстановить сессию по refreshToken из localStorage,
  // не рендерим защищённые роуты — иначе ProtectedRoute успеет редиректнуть на /login
  // ещё до того, как токен реально обновится.
  const bootstrapped = useAuthBootstrap()

  if (!bootstrapped) {
    return (
      <div className="flex min-h-svh items-center justify-center text-gray-500">
        {t('app.loading')}
      </div>
    )
  }

  return (
    <BrowserRouter>
      <LanguageSwitcher />
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/verify-email" element={<VerifyEmailPage />} />
        <Route
          path="/"
          element={
            <ProtectedRoute>
              <DashboardPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/profile"
          element={
            <ProtectedRoute>
              <ProfilePage />
            </ProtectedRoute>
          }
        />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}

export default App
