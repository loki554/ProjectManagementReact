import { useTranslation } from 'react-i18next'
import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { LanguageSwitcher } from './components/LanguageSwitcher'
import { ProtectedRoute } from './components/ProtectedRoute'
import { ToastContainer } from './components/ui/ToastContainer'
import { ProfilePage } from './pages/ProfilePage'
import { LoginPage } from './pages/auth/LoginPage'
import { RegisterPage } from './pages/auth/RegisterPage'
import { VerifyEmailPage } from './pages/auth/VerifyEmailPage'
import { ProjectLayout } from './components/layout/ProjectLayout'
import { NewProjectPage } from './pages/projects/NewProjectPage'
import { ProjectEditPage } from './pages/projects/ProjectEditPage'
import { ProjectMembersPage } from './pages/projects/ProjectMembersPage'
import { ProjectOverviewPage } from './pages/projects/ProjectOverviewPage'
import { ProjectsListPage } from './pages/projects/ProjectsListPage'
import { ProjectTaskListPage } from './pages/projects/ProjectTaskListPage'
import { ProjectTagsPage } from './pages/projects/ProjectTagsPage'
import { ProjectTasksPage } from './pages/projects/ProjectTasksPage'
import { ProjectWikiPage } from './pages/projects/ProjectWikiPage'
import { TaskCreatePage } from './pages/projects/TaskCreatePage'
import { TaskEditPage } from './pages/projects/TaskEditPage'
import { TaskViewPage } from './pages/projects/TaskViewPage'
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
        <div className="flex min-h-svh items-center justify-center text-gray-500 dark:text-gray-400">
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
        {/* Все страницы внутри проекта живут во вложенных роутах под общим
            ProjectLayout (хедер + сайдбар), их URL-ы не изменились. */}
        <Route
          path="/projects/:projectSlug"
          element={
            <ProtectedRoute>
              <ProjectLayout />
            </ProtectedRoute>
          }
        >
          <Route index element={<ProjectOverviewPage />} />
          <Route path="tasks" element={<ProjectTaskListPage />} />
          <Route path="board" element={<ProjectTasksPage />} />
          {/* Статический сегмент "new" ранжируется выше динамического :taskNumber,
              поэтому конфликт с /tasks/:taskNumber исключён. Подзадача — тот же роут
              с ?parent=<taskNumber>. */}
          <Route path="tasks/new" element={<TaskCreatePage />} />
          <Route path="tasks/:taskNumber" element={<TaskViewPage />} />
          <Route path="tasks/:taskNumber/edit" element={<TaskEditPage />} />
          <Route path="wiki" element={<ProjectWikiPage />} />
          <Route path="settings/members" element={<ProjectMembersPage />} />
          <Route path="settings/tags" element={<ProjectTagsPage />} />
          <Route path="settings/edit" element={<ProjectEditPage />} />
        </Route>
        <Route path="/" element={<Navigate to="/projects" replace />} />
        <Route path="*" element={<Navigate to="/projects" replace />} />
      </Routes>
    </>
  )
}

export default App
