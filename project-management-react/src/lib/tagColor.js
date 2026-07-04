// Тэги — кастомные, цвет выбирает владелец проекта через <input type="color">, поэтому
// нет фиксированной палитры для подбора контраста, как у status/urgency бейджей. Простая
// ~15%-alpha заливка hex-цветом под текст того же цвета — этого достаточно для читаемой пилюли
// без реализации алгоритма контрастности.
export function tagBadgeStyle(hexColor) {
  return {
    backgroundColor: `${hexColor}26`,
    color: hexColor,
  }
}
