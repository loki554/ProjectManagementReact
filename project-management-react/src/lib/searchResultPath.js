// Куда ведёт результат поиска (4.1). У комментария своей страницы нет — он живёт в треде
// задачи; у вики нет задачи, поэтому она ведёт на страницу вики проекта.
//
// Отдельный модуль от searchSnippet.jsx: там компонент, и держать рядом с ним обычную
// функцию значит ломать fast refresh (файл перестаёт экспортировать только компоненты).
export function searchResultPath(result) {
  if (result.type === 'WIKI') {
    return `/projects/${result.projectSlug}/wiki`
  }
  return `/projects/${result.projectSlug}/tasks/${result.taskNumber}`
}
