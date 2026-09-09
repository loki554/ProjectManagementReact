import { Component } from 'react'

/**
 * Единственный класс-компонент в приложении — вынужденно: ловить ошибку рендера умеют
 * только getDerivedStateFromError/componentDidCatch, хука-эквивалента в React 19 нет.
 *
 * Границ три уровня, и они не дублируют друг друга, а отвечают за разную тяжесть падения
 * (см. 5.1 в IMPROVEMENTS.md):
 *   - корневая (main.jsx) — последний рубеж, ниже неё только белый экран;
 *   - маршрутная (App.jsx) — упала страница, лечится уходом на другую;
 *   - секционные (канбан, Markdown) — упал кусок страницы, остальная страница жива.
 *
 * fallback — функция ({ error, reset }), а не готовый элемент: без доступа к reset кнопку
 * «попробовать снова» снаружи не нарисовать, а без неё восстановление опять сводится к F5.
 */
export class ErrorBoundary extends Component {
  constructor(props) {
    super(props)
    this.state = { error: null, resetKeys: props.resetKeys }
    this.reset = this.reset.bind(this)
  }

  static getDerivedStateFromError(error) {
    return { error }
  }

  componentDidCatch(error, info) {
    // Единственное место, где ошибка рендера вообще где-то фиксируется, и пока это консоль:
    // отправлять её некуда — фронтовой наблюдаемости нет вовсе (раздел 9). Зато место одно:
    // когда появится Sentry или свой /api/client-errors, менять здесь и только здесь.
    console.error(`[ErrorBoundary: ${this.props.name ?? 'unnamed'}]`, error, info?.componentStack)
  }

  // Смена resetKeys означает «условия изменились, есть смысл попробовать снова» — так
  // маршрутная граница забывает ошибку при переходе на другую страницу. Само по себе обновление
  // ошибку не забывает: сломанный компонент крутился бы в цикле «упал → перерисовался → упал», и
  // вместо экрана с объяснением человек получил бы подвисшую вкладку.
  //
  // Сброс живёт именно здесь, а не в componentDidUpdate: ошибка снимается до рендера, то есть
  // без лишнего кадра с запасным экраном на уже исправных данных.
  static getDerivedStateFromProps(props, state) {
    if (sameKeys(state.resetKeys, props.resetKeys)) {
      return null
    }
    return { error: null, resetKeys: props.resetKeys }
  }

  reset() {
    this.setState({ error: null })
  }

  render() {
    if (this.state.error !== null) {
      return this.props.fallback({ error: this.state.error, reset: this.reset })
    }
    return this.props.children
  }
}

function sameKeys(previous = [], next = []) {
  return previous.length === next.length && previous.every((key, index) => Object.is(key, next[index]))
}
