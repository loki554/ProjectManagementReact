import { apiClient } from './client'

// Зависимости между задачами (4.8). Ответ у всех трёх ручек один и тот же —
// { blockedBy, blocks } задачи из URL: панель показывает обе стороны сразу, и отдавать
// половину, заставляя клиента дочитывать вторую, незачем.
export function fetchDependencies(taskId) {
  return apiClient.get(`/tasks/${taskId}/dependencies`).then((res) => res.data)
}

// Связь в базе одна, направлений у неё два, и оба выражаются этим же вызовом с разных
// концов: «эту блокирует та» — addDependency(эта, та), «эта блокирует ту» —
// addDependency(та, эта). Отдельной ручки под второе направление нет намеренно: две
// точки входа, пишущие одну строку, — это два места, где можно перепутать сторону.
export function addDependency(blockedTaskId, blockerTaskId) {
  return apiClient
    .post(`/tasks/${blockedTaskId}/dependencies`, { blockerTaskId })
    .then((res) => res.data)
}

export function removeDependency(blockedTaskId, blockerTaskId) {
  return apiClient
    .delete(`/tasks/${blockedTaskId}/dependencies/${blockerTaskId}`)
    .then((res) => res.data)
}
