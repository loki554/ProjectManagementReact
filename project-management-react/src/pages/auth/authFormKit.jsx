export function AuthLayout({ title, children }) {
  return (
    <div className="flex min-h-svh items-center justify-center px-4">
      <div className="w-full max-w-sm rounded-lg border border-gray-200 bg-white p-8 shadow-sm dark:border-gray-700 dark:bg-gray-800">
        <h1 className="mb-6 text-2xl font-semibold text-gray-900 dark:text-gray-100">{title}</h1>
        {children}
      </div>
    </div>
  )
}
