import { useTranslation } from 'react-i18next'
import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { LanguageSwitcher } from './components/LanguageSwitcher'
import { ProtectedRoute } from './components/ProtectedRoute'
import { ErrorBoundary } from './components/errors/ErrorBoundary'
import { AppErrorScreen } from './components/errors/ErrorFallback'
import { ConfirmDialogHost } from './components/ui/ConfirmDialog'
import { ToastContainer } from './components/ui/ToastContainer'
import { InvitePage } from './pages/InvitePage'
import { NotFoundPage } from './pages/NotFoundPage'
import { ProfilePage } from './pages/ProfilePage'
import { SearchPage } from './pages/SearchPage'
import { UnsubscribePage } from './pages/UnsubscribePage'
import { ForgotPasswordPage } from './pages/auth/ForgotPasswordPage'
import { LoginPage } from './pages/auth/LoginPage'
import { RegisterPage } from './pages/auth/RegisterPage'
import { ResetPasswordPage } from './pages/auth/ResetPasswordPage'
import { VerifyEmailPage } from './pages/auth/VerifyEmailPage'
import { ProjectLayout } from './components/layout/ProjectLayout'
import { NewProjectPage } from './pages/projects/NewProjectPage'
import { ProjectDashboardPage } from './pages/projects/ProjectDashboardPage'
import { ProjectEditPage } from './pages/projects/ProjectEditPage'
import { ProjectExportPage } from './pages/projects/ProjectExportPage'
import { ProjectMembersPage } from './pages/projects/ProjectMembersPage'
import { ProjectOverviewPage } from './pages/projects/ProjectOverviewPage'
import { ProjectSprintsPage } from './pages/projects/ProjectSprintsPage'
import { ProjectTimeReportPage } from './pages/projects/ProjectTimeReportPage'
import { ProjectsListPage } from './pages/projects/ProjectsListPage'
import { ProjectTaskListPage } from './pages/projects/ProjectTaskListPage'
import { ProjectCategoriesPage } from './pages/projects/ProjectCategoriesPage'
import { ProjectTagsPage } from './pages/projects/ProjectTagsPage'
import { ProjectTaskTemplatesPage } from './pages/projects/ProjectTaskTemplatesPage'
import { ProjectTasksPage } from './pages/projects/ProjectTasksPage'
import { ProjectTrashPage } from './pages/projects/ProjectTrashPage'
import { ProjectWikiPage } from './pages/projects/ProjectWikiPage'
import { TaskCreatePage } from './pages/projects/TaskCreatePage'
import { TaskEditPage } from './pages/projects/TaskEditPage'
import { TaskViewPage } from './pages/projects/TaskViewPage'
import { useRealtimeUpdates } from './api/realtime'
import { useAuthBootstrap } from './stores/useAuthBootstrap'

function App() {
  const { t } = useTranslation()
  // Пока идёт попытка молча восстановить сессию по refreshToken из localStorage,
  // не рендерим защищённые роуты — иначе ProtectedRoute успеет редиректнуть на /login
  // ещё до того, как токен реально обновится.
  const bootstrapped = useAuthBootstrap()
  // Один поток живых обновлений на вкладку, а не на страницу (4.15): колокольчик и «мои
  // задачи» видны везде, и переоткрывать соединение на каждый переход между проектами
  // значило бы платить рукопожатием за навигацию. Здесь же, а не внутри BrowserRouter:
  // от маршрута поток не зависит вовсе, а от наличия сессии — зависит, и стор с ней
  // одинаково доступен по обе стороны условного рендера ниже.
  useRealtimeUpdates()

  return (
    <>
      {/* Вне BrowserRouter/условного рендера ниже — тост про "сессия истекла" может
          прилететь ещё во время useAuthBootstrap, до того как маршруты вообще смонтированы. */}
      <ToastContainer />
      {/* Там же и по той же причине, что тосты (5.2): спрашивают со всех экранов, а
          ответ должен пережить даже переход, который сам же и вызвал. */}
      <ConfirmDialogHost />
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
  const hasOwnHeader =
    location.pathname.startsWith('/projects') || location.pathname.startsWith('/search')

  return (
    <>
      {!hasOwnHeader && <LanguageSwitcher />}
      {/* Маршрутная граница: ошибка рендера одной страницы гасит страницу, а не вкладку.
          resetKeys по location.key, а не по pathname: ключ уникален для каждой записи истории,
          поэтому и «назад» на тот же адрес считается новой попыткой. Сама граница — внутри
          BrowserRouter и выше <Routes>: сам location.key берётся из роутера, да и пережить
          падение роутер обязан — иначе уходить со сломанной страницы было бы некуда. */}
      <ErrorBoundary
        name="route"
        resetKeys={[location.key]}
        fallback={({ error, reset }) => <AppErrorScreen error={error} onRetry={reset} />}
      >
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/verify-email" element={<VerifyEmailPage />} />
          <Route path="/forgot-password" element={<ForgotPasswordPage />} />
          <Route path="/reset-password" element={<ResetPasswordPage />} />
          {/* Не под ProtectedRoute: по этой ссылке приходят из письма, и чаще всего — ещё
              не имея аккаунта. Страница сама решает, что показать вошедшему и анонимному
              (см. InvitePage). */}
          <Route path="/invite" element={<InvitePage />} />
          {/* Тоже не под ProtectedRoute: по этой ссылке приходят из письма-уведомления, и
              требовать входа ради «перестаньте мне писать» — верный способ получить вместо
              отписки жалобу на спам (см. UnsubscribePage). */}
          <Route path="/unsubscribe" element={<UnsubscribePage />} />
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
          {/* Выдача поиска — свой роут, а не оверлей: состояние поиска целиком лежит в
              query-параметрах, чтобы ссылкой можно было поделиться (см. SearchPage). */}
          <Route
            path="/search"
            element={
              <ProtectedRoute>
                <SearchPage />
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
            {/* Спринты (4.9) — отдельная страница проекта, не раздел настроек: это не
                справочник вроде тэгов, а рабочий экран, на который ходят каждый день. */}
            <Route path="sprints" element={<ProjectSprintsPage />} />
            {/* Дашборд (4.11) и отчёт по времени (4.10) — тоже рабочие экраны, а не
                настройки: оба только читают то, что трекер уже собрал, и оба отвечают на
                вопросы, которые задают в понедельник утром, а не при заведении проекта. */}
            <Route path="dashboard" element={<ProjectDashboardPage />} />
            <Route path="reports/time" element={<ProjectTimeReportPage />} />
            {/* Выгрузка (4.12) — тоже рабочий экран, но редкий: за ней приходят раз в
                квартал, когда нужен архив или сводная таблица, поэтому в сайдбаре она
                стоит внизу, рядом с корзиной. */}
            <Route path="export" element={<ProjectExportPage />} />
            <Route path="trash" element={<ProjectTrashPage />} />
            {/* Статический сегмент "new" ранжируется выше динамического :taskNumber,
                поэтому конфликт с /tasks/:taskNumber исключён. Подзадача — тот же роут
                с ?parent=<taskNumber>. */}
            <Route path="tasks/new" element={<TaskCreatePage />} />
            <Route path="tasks/:taskNumber" element={<TaskViewPage />} />
            <Route path="tasks/:taskNumber/edit" element={<TaskEditPage />} />
            <Route path="wiki" element={<ProjectWikiPage />} />
            <Route path="settings/members" element={<ProjectMembersPage />} />
            <Route path="settings/tags" element={<ProjectTagsPage />} />
            <Route path="settings/categories" element={<ProjectCategoriesPage />} />
            {/* Шаблоны задач (4.13) — в настройках, рядом с тэгами и категориями: это
                справочник проекта, а не рабочий экран. Пользуются им не отсюда, а из формы
                заведения задачи, где шаблон и выбирают. */}
            <Route path="settings/templates" element={<ProjectTaskTemplatesPage />} />
            <Route path="settings/edit" element={<ProjectEditPage />} />
          </Route>
          <Route path="/" element={<Navigate to="/projects" replace />} />
          {/* Настоящая 404, а не редирект на список проектов (5.3): редирект врал, будто по
              адресу что-то есть, и прятал саму ошибку — опечатка в ссылке на задачу выглядела
              как «задачу удалили». Сюда же попадают несуществующие разделы внутри проекта: у
              вложенных маршрутов своего "*" нет, и несовпавший путь доходит до этого. */}
          <Route path="*" element={<NotFoundPage />} />
        </Routes>
      </ErrorBoundary>
    </>
  )
}

export default App
