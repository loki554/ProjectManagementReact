import { useRef, useState } from 'react'

export function AttachmentDropzone({ onFileSelected, disabled, pending, accept, hintText, activeText }) {
  const [isDragOver, setIsDragOver] = useState(false)
  const inputRef = useRef(null)

  function handleDrop(event) {
    event.preventDefault()
    setIsDragOver(false)
    const file = event.dataTransfer.files?.[0]
    if (file) onFileSelected(file)
  }

  function handleInputChange(event) {
    const file = event.target.files?.[0]
    if (file) onFileSelected(file)
    // сбрасываем value, иначе повторный выбор того же файла не вызовет onChange
    event.target.value = ''
  }

  return (
    <div
      onDragOver={(event) => {
        event.preventDefault()
        if (!disabled) setIsDragOver(true)
      }}
      onDragLeave={() => setIsDragOver(false)}
      onDrop={disabled ? undefined : handleDrop}
      onClick={() => !disabled && inputRef.current?.click()}
      role="button"
      tabIndex={disabled ? -1 : 0}
      className={`rounded-md border-2 border-dashed p-4 text-center text-sm transition-colors ${
        disabled ? 'pointer-events-none opacity-60' : 'cursor-pointer hover:border-purple-400'
      } ${isDragOver ? 'border-purple-500 bg-purple-50 text-purple-700' : 'border-gray-300 text-gray-500'}`}
    >
      <input
        ref={inputRef}
        type="file"
        accept={accept}
        className="hidden"
        onChange={handleInputChange}
        disabled={disabled}
      />
      {pending ? activeText : hintText}
    </div>
  )
}
