import MDEditor from '@uiw/react-md-editor'
import { useTranslation } from 'react-i18next'
import { ErrorBoundary } from '../errors/ErrorBoundary'
import { inputClass } from '../ui/FormKit'
import { useUiStore } from '../../stores/uiStore'

// maxLength пробрасывается в textarea редактора и должен совпадать с @Size на соответствующем
// DTO бэкенда (описание задачи — 20000, вики проекта — 100000). Браузер сам обрежет и ввод,
// и вставку, так что до 400-й с общим "проверьте форму" дело не доходит.
function RichEditor({ value, onChange, placeholder, maxLength }) {
  const theme = useUiStore((state) => state.theme)
  return (
    <div data-color-mode={theme}>
      <MDEditor
        value={value ?? ''}
        onChange={(next) => onChange(next ?? '')}
        height={280}
        preview="live"
        textareaProps={{ placeholder, maxLength }}
      />
    </div>
  )
}

// Запасной редактор на случай падения @uiw/react-md-editor: та же самая textarea, только
// без панели инструментов и превью. Markdown — обычный текст, и писать его руками можно
// без всякого редактора; ценность здесь в том, что человек не теряет ни форму, ни уже
// набранное, ни возможность нажать "Сохранить".
function PlainEditor({ value, onChange, placeholder, maxLength }) {
  const { t } = useTranslation()
  return (
    <div>
      <p className="mb-1 text-xs text-amber-700 dark:text-amber-400">{t('errorBoundary.editorFallback')}</p>
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
 * Граница вокруг редактора — внутри самого компонента, а не в каждой из вызывающих форм
 * (описание задачи, вики проекта): защищаться нужно от конкретной сторонней библиотеки, и
 * забыть обернуть очередное место использования тут просто негде.
 *
 * Кнопки "попробовать снова" у этого fallback'а нет намеренно: упавший на этих же данных
 * редактор упадёт снова, а вот запасная textarea работает — и предлагать вместо неё
 * повторную попытку значило бы предлагать снова остаться без поля ввода.
 */
export function MarkdownEditor(props) {
  return (
    <ErrorBoundary name="markdown-editor" fallback={() => <PlainEditor {...props} />}>
      <RichEditor {...props} />
    </ErrorBoundary>
  )
}
