import { describe, expect, it } from 'vitest'
import { collectInvalidations } from './realtime'

function project(type, overrides = {}) {
  return { scope: 'project', type, projectId: 'p1', taskId: null, actorId: 'someone', ...overrides }
}

/**
 * Отображение «пришло событие → что перечитать» (4.15).
 *
 * Ключи здесь намеренно крупные, и проверяется именно это. Точечная инвалидация выглядит
 * бережнее, но требует полной карты «событие → все запросы, на которые оно влияет», а
 * забытая строчка в такой карте не ломается, а тихо оставляет один экран несвежим — ровно
 * ту проблему, ради которой всё и делалось. Крупные ключи ничего не стоят: react-query
 * перезапрашивает только то, что кто-то сейчас наблюдает.
 */
describe('collectInvalidations', () => {
  it('правка задачи протухает и проект, и задачи', () => {
    expect(collectInvalidations([project('task_status_changed', { taskId: 't1' })])).toEqual([
      ['projects', 'p1'],
      ['tasks'],
    ])
  })

  // ['tasks'] — это и одна задача, и её подзадачи, и кросс-проектный список «мои активные»:
  // назначение задачи в чужом проекте меняет мой список ровно так же, как в своём.
  it('комментарий тоже трогает задачи: счётчики и лента живут рядом', () => {
    expect(collectInvalidations([project('comment_added', { taskId: 't1' })])).toContainEqual(['tasks'])
  })

  // ['projects', id] не покрывает ни список проектов, ни проект, найденный по слагу, —
  // они лежат рядом с этим ключом, а не под ним.
  it('правка самого проекта поднимается до всего списка проектов', () => {
    expect(collectInvalidations([project('project_updated')])).toEqual([['projects']])
  })

  it('смена состава участников — тоже: исключённому проект пропадает из списка', () => {
    expect(collectInvalidations([project('member_removed')])).toEqual([['projects']])
  })

  it('спринт трогает проект, но не задачи', () => {
    expect(collectInvalidations([project('sprint_created')])).toEqual([['projects', 'p1']])
  })

  it('личное уведомление протухает колокольчик', () => {
    const message = { scope: 'notification', type: 'task_assigned', projectId: 'p1', taskId: 't1' }
    expect(collectInvalidations([message])).toEqual([['notifications'], ['projects', 'p1'], ['tasks']])
  })

  // Одно действие человека — это несколько записей в ленте (сменил статус и исполнителя),
  // а массовая правка (4.6) даёт по записи на задачу. Схлопывание окном в 300 мс имеет
  // смысл только если повторы действительно схлопываются в один ключ.
  it('пачка событий одного проекта схлопывается в один набор ключей', () => {
    const keys = collectInvalidations([
      project('task_status_changed', { taskId: 't1' }),
      project('task_assignee_changed', { taskId: 't1' }),
      project('task_urgency_changed', { taskId: 't2' }),
    ])
    expect(keys).toEqual([['projects', 'p1'], ['tasks']])
  })

  it('события двух проектов не смешиваются', () => {
    const keys = collectInvalidations([
      project('sprint_created'),
      project('sprint_created', { projectId: 'p2' }),
    ])
    expect(keys).toEqual([['projects', 'p1'], ['projects', 'p2']])
  })

  it('пустая пачка ничего не протухает', () => {
    expect(collectInvalidations([])).toEqual([])
  })
})
