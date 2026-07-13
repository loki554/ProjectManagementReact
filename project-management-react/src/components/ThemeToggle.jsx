import { Moon, Sun } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useUiStore } from '../stores/uiStore'

export function ThemeToggle() {
  const { t } = useTranslation()
  const theme = useUiStore((state) => state.theme)
  const toggleTheme = useUiStore((state) => state.toggleTheme)
  const isDark = theme === 'dark'

  return (
    <button
      type="button"
      onClick={toggleTheme}
      aria-label={t(isDark ? 'theme.switchToLight' : 'theme.switchToDark')}
      title={t(isDark ? 'theme.switchToLight' : 'theme.switchToDark')}
      className="rounded p-1.5 text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-700"
    >
      {isDark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
    </button>
  )
}
