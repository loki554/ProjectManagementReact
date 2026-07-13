import MDEditor from '@uiw/react-md-editor'
import { useUiStore } from '../../stores/uiStore'

export function MarkdownEditor({ value, onChange, placeholder }) {
  const theme = useUiStore((state) => state.theme)
  return (
    <div data-color-mode={theme}>
      <MDEditor
        value={value ?? ''}
        onChange={(next) => onChange(next ?? '')}
        height={280}
        preview="live"
        textareaProps={{ placeholder }}
      />
    </div>
  )
}
