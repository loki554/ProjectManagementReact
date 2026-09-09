import { MapPinOff } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link, useLocation } from 'react-router-dom'
import { StatusScreen } from '../components/ui/StatusScreen'
import { primaryButtonClass } from '../components/ui/FormKit'

/**
 * Настоящая 404 вместо прежнего молчаливого редиректа на список проектов (5.3).
 *
 * Редирект врал дважды: он утверждал, что по адресу что-то есть (раз уж перенаправляет), и
 * прятал сам факт ошибки — опечатка в ссылке на задачу выглядела как «задачу удалили».
 *
 * Не под ProtectedRoute намеренно: пришедшему по битой ссылке анониму честнее сказать «тут
 * ничего нет», чем отправить его на форму входа за страницей, которой не существует ни для
 * кого. А кнопка «к проектам» его на этот вход и приведёт — но уже как следствие его
 * собственного выбора, а не подмены.
 */
export function NotFoundPage() {
  const { t } = useTranslation()
  const location = useLocation()

  return (
    <StatusScreen
      icon={MapPinOff}
      title={t('notFound.title')}
      description={t('notFound.description')}
      // Адрес показан целиком: чаще всего это опечатка в номере задачи или в имени
      // проекта, и увидеть её можно только вместе с самим адресом.
      hint={`${location.pathname}${location.search}`}
    >
      <Link to="/projects" className={primaryButtonClass}>
        {t('notFound.toProjects')}
      </Link>
    </StatusScreen>
  )
}
