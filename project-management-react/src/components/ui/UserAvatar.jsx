import { useAuthenticatedImage } from '../../lib/useAuthenticatedImage'

// Аватар с фолбэком на инициалы (фамилия+имя). Отдельный компонент, т.к.
// useAuthenticatedImage — хук и в map по списку его звать нельзя (тот же приём,
// что ActorAvatar в ActivityFeed и MemberAvatar на overview).
// user: UserSummary { avatarUrl, lastName, firstName } | null.
export function UserAvatar({ user, sizeClass = 'h-8 w-8' }) {
  const avatarUrl = useAuthenticatedImage(user?.avatarUrl)
  return (
    <div
      className={`flex ${sizeClass} shrink-0 items-center justify-center overflow-hidden rounded-full bg-gray-200 text-[10px] font-medium text-gray-600`}
    >
      {avatarUrl ? (
        <img src={avatarUrl} alt="" className="h-full w-full object-cover" />
      ) : (
        <span aria-hidden="true">
          {user ? (user.lastName?.[0] ?? '') + (user.firstName?.[0] ?? '') : '?'}
        </span>
      )}
    </div>
  )
}
