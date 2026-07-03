import i18n from 'i18next'
import LanguageDetector from 'i18next-browser-languagedetector'
import { initReactI18next } from 'react-i18next'
import de from './locales/de.json'
import en from './locales/en.json'
import ru from './locales/ru.json'

export const SUPPORTED_LANGUAGES = ['ru', 'en', 'de']

i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    resources: {
      ru: { translation: ru },
      en: { translation: en },
      de: { translation: de },
    },
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

export default i18n
