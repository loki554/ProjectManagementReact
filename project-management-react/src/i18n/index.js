import i18n from 'i18next'
import LanguageDetector from 'i18next-browser-languagedetector'
import { initReactI18next } from 'react-i18next'

export const SUPPORTED_LANGUAGES = ['ru', 'en', 'de']

/**
 * Словари грузятся по одному и по требованию (5.4). Раньше все три импортировались статически
 * и уезжали в основной файл целиком: человек, работающий по-русски, вёз с собой английский и
 * немецкий на каждой загрузке страницы.
 *
 * Динамический import(), а не i18next-http-backend, как предполагал разбор: словари остаются
 * частью сборки — с хешем в имени, с общим кэшированием и без отдельной папки в public, — а
 * делит их на файлы тот же сборщик, что и остальной код. Backend добавил бы зависимость и
 * второй, самодельный способ доставки статики ровно ради того же результата.
 */
const LOADERS = {
  ru: () => import('./locales/ru.json'),
  en: () => import('./locales/en.json'),
  de: () => import('./locales/de.json'),
}

i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    // resources пустые: язык подгружается ниже, до первого рендера (см. loadDetectedLanguage).
    resources: {},
    fallbackLng: 'en',
    supportedLngs: SUPPORTED_LANGUAGES,
    // Браузер отдаёт языки с регионом (ru-RU, en-US, de-DE) — отбрасываем регион,
    // чтобы они попадали на наши ru/en/de ресурсы, а не на fallbackLng.
    load: 'languageOnly',
    // Порядок важен: сначала смотрим, не переключал ли пользователь язык вручную
    // (тогда он лежит в localStorage), и только если нет — определяем по языку браузера.
    detection: {
      order: ['localStorage', 'navigator'],
      caches: ['localStorage'],
      lookupLocalStorage: 'pmtracker-language',
    },
    interpolation: {
      escapeValue: false, // React сам экранирует вывод, дополнительное экранирование не нужно
    },
  })

/** Догружает словарь, если его ещё нет. Возвращает язык, который в итоге доступен. */
export async function loadLanguage(language) {
  const target = SUPPORTED_LANGUAGES.includes(language) ? language : 'en'
  if (!i18n.hasResourceBundle(target, 'translation')) {
    const module = await LOADERS[target]()
    i18n.addResourceBundle(target, 'translation', module.default)
  }
  return target
}

/**
 * Язык определён детектором ещё в init, но словаря к нему нет — грузим до первого рендера
 * (см. main.jsx). Иначе первый кадр был бы на ключах вместо слов.
 */
export function loadDetectedLanguage() {
  return loadLanguage(i18n.language)
}

/**
 * Переключение языка вручную: сначала словарь, потом переключение. В обратном порядке
 * интерфейс на мгновение показал бы ключи вместо текста.
 *
 * Загруженный словарь не выгружается: переключают язык редко, а вот туда-сюда — вполне.
 */
export async function changeLanguage(language) {
  await loadLanguage(language)
  await i18n.changeLanguage(language)
}

export default i18n
