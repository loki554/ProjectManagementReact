import MDEditor from '@uiw/react-md-editor/nohighlight'
import { useUiStore } from '../../stores/uiStore'

// Отдельный файл, чтобы @uiw/react-md-editor уезжал в свой чанк и грузился только тогда,
// когда на экране действительно есть поле ввода Markdown (5.4). Он весит больше, чем всё
// остальное приложение вместе взятое, и до этого приезжал вместе со страницей — то есть
// форма задачи ждала его целиком, прежде чем показать хотя бы поле заголовка.
//
// Вход nohighlight, а не обычный: он без refractor/prism, и это 898 кБ против 272 кБ —
// больше двух третей веса редактора уходило на подсветку синтаксиса в превью. При этом сама
// страница задачи код никогда и не подсвечивала (MarkdownRenderer идёт без rehype-highlight),
// то есть превью обещало цвета, которых после сохранения не будет. Теперь оно показывает то же,
// что увидят читатели, и стоит втрое дешевле.
//
// default-экспорт, а не именованный, как везде в проекте: так его ждёт React.lazy, и
// оборачивать импорт ради одного компонента незачем.
//
// maxLength пробрасывается в textarea редактора и должен совпадать с @Size на соответствующем
// DTO бэкенда (описание задачи — 20000, вики проекта — 100000). Браузер сам обрежет и ввод,
// и вставку, так что до 400-й с общим "проверьте форму" дело не доходит.
export default function RichEditor({ value, onChange, placeholder, maxLength }) {
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
