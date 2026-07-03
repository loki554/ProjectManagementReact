import MDEditor from '@uiw/react-md-editor'

// Тёмная тема не в скоупе MVP (см. §9 плана) — принудительно light.
export function MarkdownEditor({ value, onChange, placeholder }) {
  return (
    <div data-color-mode="light">
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
