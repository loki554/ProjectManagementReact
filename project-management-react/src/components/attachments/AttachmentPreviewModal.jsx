import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'

const ZOOM_STEP = 0.25
const ZOOM_MIN = 0.5
const ZOOM_MAX = 3

// Полноразмерный просмотр картинки-вложения (ответ на открытый вопрос 7.7.3: клик по
// превью в списке открывает полный размер + зум). imageUrl — уже готовый blob-URL
// (переиспользуется из AttachmentThumbnail, повторного запроса к бэку не делаем).
export function AttachmentPreviewModal({ imageUrl, filename, onClose }) {
  const { t } = useTranslation()
  const [zoom, setZoom] = useState(1)

  useEffect(() => {
    function handleKeyDown(event) {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [onClose])

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-4"
      onClick={onClose}
    >
      <div
        className="flex max-h-full max-w-full flex-col items-center gap-3"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex items-center gap-2 rounded-md bg-white px-3 py-1.5 text-sm shadow">
          <button
            type="button"
            onClick={() => setZoom((z) => Math.max(ZOOM_MIN, z - ZOOM_STEP))}
            className="px-2 text-base font-medium text-gray-700 hover:text-purple-700"
            aria-label={t('tasks.attachments.zoomOut')}
          >
            −
          </button>
          <span className="w-12 text-center text-gray-600">{Math.round(zoom * 100)}%</span>
          <button
            type="button"
            onClick={() => setZoom((z) => Math.min(ZOOM_MAX, z + ZOOM_STEP))}
            className="px-2 text-base font-medium text-gray-700 hover:text-purple-700"
            aria-label={t('tasks.attachments.zoomIn')}
          >
            +
          </button>
          <button
            type="button"
            onClick={() => setZoom(1)}
            className="ml-1 text-xs text-gray-500 hover:text-purple-700"
          >
            {t('tasks.attachments.resetZoom')}
          </button>
          <button
            type="button"
            onClick={onClose}
            className="ml-3 text-gray-500 hover:text-red-600"
            aria-label={t('tasks.attachments.closePreview')}
          >
            ✕
          </button>
        </div>
        <div className="max-h-[80vh] max-w-[90vw] overflow-auto rounded bg-white p-2">
          <img
            src={imageUrl}
            alt={filename}
            style={{ transform: `scale(${zoom})`, transformOrigin: 'top left' }}
            className="block"
          />
        </div>
      </div>
    </div>
  )
}
