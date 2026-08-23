import MDEditor from '@uiw/react-md-editor'
import { useUiStore } from '../../stores/uiStore'

// maxLength пробрасывается в textarea редактора и должен совпадать с @Size на соответствующем
// DTO бэкенда (описание задачи — 20000, вики проекта — 100000). Браузер сам обрежет и ввод,
// и вставку, так что до 400-й с общим "проверьте форму" дело не доходит.
export function MarkdownEditor({ value, onChange, placeholder, maxLength }) {
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
