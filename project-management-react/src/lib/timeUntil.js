// dueDate теперь datetime (ISO instant с бэкенда), не просто дата — считаем реальную
// разницу во времени, а не разницу календарных дат, чтобы часовой отсчёт в последние
// сутки был точным (а не "сегодня/завтра" по границе полуночи).
export function msUntil(dueDateIso) {
  return new Date(dueDateIso).getTime() - Date.now()
}
