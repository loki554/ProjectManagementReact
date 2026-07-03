import { useTranslation } from 'react-i18next'
import { SUPPORTED_LANGUAGES } from '../i18n'

export function LanguageSwitcher() {
  const { t, i18n } = useTranslation()

  return (
    <div className="fixed top-4 right-4 flex gap-1 rounded-md border border-gray-200 bg-white p-1 shadow-sm">
      {SUPPORTED_LANGUAGES.map((lng) => (
        <button
          key={lng}
          type="button"
          onClick={() => i18n.changeLanguage(lng)}
          aria-label={t(`language.${lng}`)}
          className={`rounded px-2 py-1 text-xs font-medium uppercase ${
            i18n.resolvedLanguage === lng
              ? 'bg-purple-600 text-white'
              : 'text-gray-600 hover:bg-gray-100'
          }`}
        >
          {lng}
        </button>
      ))}
    </div>
  )
}
