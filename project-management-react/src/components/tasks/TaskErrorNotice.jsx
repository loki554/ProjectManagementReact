import { FileQuestionMark } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { getErrorCode, getLocalizedErrorMessage } from '../../lib/errorMessage'
import { StatusScreen } from '../ui/StatusScreen'
import { primaryButtonClass, secondaryButtonClass } from '../ui/FormKit'

/**
 * Задача по номеру из адреса не открылась (5.3). Случаев ровно два, и путать их нельзя.
 *
 * Номера задач в этом трекере короткие и последовательные (#7, #12), то есть промахнуться
 * ссылкой куда легче, чем в системе с UUID: «#17 вместо #71» — обычное дело. Поэтому у
 * TASK_NOT_FOUND свой экран, и в нём названы обе настоящие причины: опечатка в номере и
 * удаление. Вторая проверяемая — задача лежит в корзине 30 дней (3.5), и ссылка туда
 * стоит рядом.
 *
 * Всё остальное (сеть, 500, отобранные права) — обычная ошибка, и выдавать её за
 * ненайденную задачу нельзя: человек пошёл бы искать опечатку там, где надо просто
 * повторить попытку.
 */
export function TaskErrorNotice({ error, projectSlug }) {
  const { t } = useTranslation()

  if (getErrorCode(error) !== 'TASK_NOT_FOUND') {
    return <p className="mt-4 text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(error, t)}</p>
  }

  return (
    <StatusScreen
      icon={FileQuestionMark}
      title={t('notFound.taskTitle')}
      description={t('notFound.taskDescription')}
    >
      <Link to={`/projects/${projectSlug}/board`} className={primaryButtonClass}>
        {t('notFound.toBoard')}
      </Link>
      <Link to={`/projects/${projectSlug}/trash`} className={secondaryButtonClass}>
        {t('notFound.toTrash')}
      </Link>
    </StatusScreen>
  )
}
