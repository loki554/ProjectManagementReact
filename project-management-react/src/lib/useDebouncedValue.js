import { useEffect, useState } from 'react'

// Отложенное значение для полей, которые дёргают сервер на каждый ввод (поиск по названию
// в списке задач). Пока фильтрация была клиентской, задержка была не нужна — фильтровался
// уже загруженный массив; теперь каждый символ это запрос.
export function useDebouncedValue(value, delayMs = 300) {
  const [debounced, setDebounced] = useState(value)

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs)
    return () => clearTimeout(timer)
  }, [value, delayMs])

  return debounced
}
