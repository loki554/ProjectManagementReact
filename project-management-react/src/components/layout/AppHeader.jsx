import { useMutation } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { logout } from '../../api/authApi'
import { SUPPORTED_LANGUAGES } from '../../i18n'
import { useAuthenticatedImage } from '../../lib/useAuthenticatedImage'
import { useAuthStore } from '../../stores/authStore'

export function AppHeader() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const user = useAuthStore((state) => state.user)
  const refreshToken = useAuthStore((state) => state.refreshToken)
  const clearSession = useAuthStore((state) => state.clearSession)
  const avatarUrl = useAuthenticatedImage(user?.avatarUrl)

  const [menuOpen, setMenuOpen] = useState(false)
  const menuRef = useRef(null)

  useEffect(() => {
    function handleClickOutside(event) {
      if (menuRef.current && !menuRef.current.contains(event.target)) {
        setMenuOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  const logoutMutation = useMutation({
    mutationFn: () => logout(refreshToken),
    onSettled: () => {
      clearSession()
      navigate('/login', { replace: true })
    },
  })

  return (
    <header className="flex items-center justify-between border-b border-gray-200 bg-white px-4 py-3">
      <Link to="/projects" className="text-lg font-semibold text-gray-900">
        {t('app.name')}
      </Link>

      <div className="flex items-center gap-3">
        <div className="flex gap-1 rounded-md border border-gray-200 p-1">
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

        <div className="relative" ref={menuRef}>
          <button
            type="button"
            onClick={() => setMenuOpen((open) => !open)}
            className="flex items-center gap-2 rounded-md px-2 py-1 hover:bg-gray-50"
          >
            <div className="h-8 w-8 shrink-0 overflow-hidden rounded-full bg-gray-200">
              {avatarUrl && <img src={avatarUrl} alt="" className="h-full w-full object-cover" />}
            </div>
            <span aria-hidden="true" className="text-gray-400">
              ▾
            </span>
          </button>

          {menuOpen && (
            <div className="absolute right-0 z-10 mt-2 w-48 rounded-md border border-gray-200 bg-white py-1 shadow-lg">
              <div className="truncate border-b border-gray-100 px-4 py-2 text-sm text-gray-500">
                {user?.firstName + " " + user?.lastName}
              </div>
              <Link
                to="/profile"
                onClick={() => setMenuOpen(false)}
                className="block px-4 py-2 text-sm text-gray-700 hover:bg-gray-50"
              >
                {t('header.profile')}
              </Link>
              <button
                type="button"
                onClick={() => logoutMutation.mutate()}
                disabled={logoutMutation.isPending}
                className="block w-full px-4 py-2 text-left text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-60"
              >
                {logoutMutation.isPending ? t('header.loggingOut') : t('header.logout')}
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
  )
}
