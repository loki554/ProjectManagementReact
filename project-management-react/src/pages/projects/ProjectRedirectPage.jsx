import { Navigate, useParams } from 'react-router-dom'

// /projects/:projectSlug сам по себе не рендерит контент — канбан-доска (Phase 5)
// станет "домом" проекта, а сейчас туда же указывает временная заглушка.
export function ProjectRedirectPage() {
  const { projectSlug } = useParams()
  return <Navigate to={`/projects/${projectSlug}/board`} replace />
}
