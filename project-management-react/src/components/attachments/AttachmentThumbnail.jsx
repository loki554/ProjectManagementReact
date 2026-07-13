import { isImageAttachment } from '../../lib/constants'
import { useAuthenticatedImage } from '../../lib/useAuthenticatedImage'

function extensionLabel(filename) {
  const dotIndex = filename.lastIndexOf('.')
  return dotIndex >= 0 ? filename.slice(dotIndex + 1, dotIndex + 5) : '?'
}

// Небольшая превьюшка в списке вложений: для изображений — уменьшенная (через CSS,
// без серверной генерации миниатюр — вне скоупа MVP) картинка, по клику открывающая
// AttachmentPreviewModal в полный размер; для остальных типов — просто иконка-заглушка
// с расширением файла, без клика (сам файл скачивается по имени в списке, см. TaskViewPage).
export function AttachmentThumbnail({ attachment, onPreview }) {
  const isImage = isImageAttachment(attachment)
  const imageUrl = useAuthenticatedImage(isImage ? `/attachments/${attachment.id}/download` : null)

  if (!isImage) {
    return (
      <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded bg-gray-100 text-[10px] font-medium uppercase text-gray-500 dark:bg-gray-700 dark:text-gray-400">
        {extensionLabel(attachment.originalFilename)}
      </div>
    )
  }

  return (
    <button
      type="button"
      onClick={() => imageUrl && onPreview(imageUrl)}
      disabled={!imageUrl}
      className="h-12 w-12 shrink-0 overflow-hidden rounded bg-gray-100 dark:bg-gray-700"
    >
      {imageUrl ? (
        <img
          src={imageUrl}
          alt={attachment.originalFilename}
          className="h-full w-full object-cover"
        />
      ) : (
        <div className="h-full w-full animate-pulse bg-gray-200 dark:bg-gray-600" />
      )}
    </button>
  )
}
