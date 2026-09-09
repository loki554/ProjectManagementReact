import { Suspense, lazy } from 'react'
import { useTranslation } from 'react-i18next'
import { ErrorBoundary } from '../errors/ErrorBoundary'
import { inputClass } from '../ui/FormKit'

const RichEditor = lazy(() => import('./RichEditor'))

// Простая textarea вместо редактора: без панели инструментов и превью, но Markdown — обычный
// текст, и писать его руками можно без всякого редактора. Служит сразу двум случаям — пока
// редактор едет (5.4) и если он упал (5.1), — и в обоих человек не теряет ни форму, ни уже
// набранное, ни возможность нажать «Сохранить».
function PlainEditor({ value, onChange, placeholder, maxLength, notice }) {
  return (
    <div>
      {notice && <p className="mb-1 text-xs text-amber-700 dark:text-amber-400">{notice}</p>}
      <textarea
        className={`${inputClass} h-70 font-mono`}
        value={value ?? ''}
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
        maxLength={maxLength}
      />
    </div>
  )
}

/**
 * Редактор приезжает отдельным файлом и не задерживает форму (5.4), а если не приедет или
 * упадёт — остаётся textarea (5.1). Обе обёртки стоят внутри самого компонента, а не в
 * каждой из вызывающих форм (описание задачи, вики, шаблоны): и грузить по требованию, и
 * защищаться нужно от конкретной сторонней библиотеки, а не от конкретной страницы, — и
 * забыть про это в очередном месте использования тут просто негде.
 *
 * Пока файл едет, показывается работающая textarea, а не серая заглушка. Это осознанный
 * размен: на медленной связи заглушка на несколько секунд запирает форму, ради которой
 * человек сюда и пришёл, а textarea принимает текст с первой секунды. Плата — редактор
 * подменит её под курсором, если печатать начали раньше, чем он доехал; набранное при этом
 * не теряется (значение живёт в форме), теряется только позиция курсора. Загрузка начинается
 * вместе с рендером формы, так что случается это редко.
 *
 * Кнопки «попробовать снова» у аварийного случая нет намеренно: упавший на этих же данных
 * редактор упадёт снова, а textarea работает — предлагать вместо неё повторную попытку
 * значило бы предлагать снова остаться без поля ввода.
 */
export function MarkdownEditor(props) {
  const { t } = useTranslation()

  return (
    <ErrorBoundary
      name="markdown-editor"
      fallback={() => <PlainEditor {...props} notice={t('errorBoundary.editorFallback')} />}
    >
      {/* Без notice: то, что редактор ещё едет, — не новость, о которой стоит сообщать
          строкой над полем. Она бы и мелькала на каждой быстрой загрузке. */}
      <Suspense fallback={<PlainEditor {...props} />}>
        <RichEditor {...props} />
      </Suspense>
    </ErrorBoundary>
  )
}
