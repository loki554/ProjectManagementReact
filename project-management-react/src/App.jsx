import { useTranslation } from 'react-i18next'
import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { LanguageSwitcher } from './components/LanguageSwitcher'
import { ProtectedRoute } from './components/ProtectedRoute'
import { ToastContainer } from './components/ui/ToastContainer'
import { ProfilePage } from './pages/ProfilePage'
import { LoginPage } from './pages/auth/LoginPage'
import { RegisterPage } from './pages/auth/RegisterPage'
import { VerifyEmailPage } from './pages/auth/VerifyEmailPage'
import { NewProjectPage } from './pages/projects/NewProjectPage'
import { ProjectMembersPage } from './pages/projects/ProjectMembersPage'
import { ProjectRedirectPage } from './pages/projects/ProjectRedirectPage'
import { ProjectsListPage } from './pages/projects/ProjectsListPage'
import { ProjectTagsPage } from './pages/projects/ProjectTagsPage'
import { ProjectTasksPage } from './pages/projects/ProjectTasksPage'
import { TaskDetailPage } from './pages/projects/TaskDetailPage'
import { useAuthBootstrap } from './stores/useAuthBootstrap'

function App() {
  const { t } = useTranslation()
  // Пока идёт попытка молча восстановить сессию по refreshToken из localStorage,
  // не рендерим защищённые роуты — иначе ProtectedRoute успеет редиректнуть на /login
  // ещё до того, как токен реально обновится.
  const bootstrapped = useAuthBootstrap()

  return (
    <>
      {/* Вне BrowserRouter/условного рендера ниже — тост про "сессия истекла" может
          прилететь ещё во время useAuthBootstrap, до того как маршруты вообще смонтированы. */}
      <ToastContainer />
      {!bootstrapped ? (
        <div className="flex min-h-svh items-center justify-center text-gray-500">
          {t('app.loading')}
        </div>
      ) : (
        <BrowserRouter>
          <AppRoutes />
        </BrowserRouter>
      )}
    </>
  )
}

// AppHeader (см. /projects/*) уже содержит свой переключатель языка — отдельный
// плавающий LanguageSwitcher нужен только там, где своего хедера ещё нет
// (auth-страницы, профиль).
function AppRoutes() {
  const location = useLocation()
  const hasOwnHeader = location.pathname.startsWith('/projects')

  return (
    <>
      {!hasOwnHeader && <LanguageSwitcher />}
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/verify-email" element={<VerifyEmailPage />} />
        <Route
          path="/profile"
          element={
            <ProtectedRoute>
              <ProfilePage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/projects"
          element={
            <ProtectedRoute>
              <ProjectsListPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/projects/new"
          element={
            <ProtectedRoute>
              <NewProjectPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/projects/:projectSlug/settings/members"
          element={
            <ProtectedRoute>
              <ProjectMembersPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/projects/:projectSlug/settings/tags"
          element={
            <ProtectedRoute>
              <ProjectTagsPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/projects/:projectSlug/board"
          element={
            <ProtectedRoute>
              <ProjectTasksPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/projects/:projectSlug/tasks/:taskNumber"
          element={
            <ProtectedRoute>
              <TaskDetailPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/projects/:projectSlug"
          element={
            <ProtectedRoute>
              <ProjectRedirectPage />
            </ProtectedRoute>
          }
        />
        <Route path="/" element={<Navigate to="/projects" replace />} />
        <Route path="*" element={<Navigate to="/projects" replace />} />
      </Routes>
    </>
  )
}

export default App
