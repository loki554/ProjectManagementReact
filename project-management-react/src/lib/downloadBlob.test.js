import { describe, expect, it } from 'vitest'
import { filenameFromContentDisposition } from './downloadBlob'

// Имя файла собирает сервер (см. ExportService.filename и TimeReportService.exportCsv), и
// разбор его заголовка — ровно то место, где ошибка не заметна: файл всё равно скачается,
// просто назовётся не так. Поэтому обе формы заголовка и оба способа его не получить
// проверяются тестом, а не глазами.
describe('filenameFromContentDisposition', () => {
  it('берёт имя из формы RFC 5987, которую отдаёт сервер', () => {
    const header = "attachment; filename*=UTF-8''time-report-my-project-2026-09-01_2026-09-30.csv"
    expect(filenameFromContentDisposition(header, 'report.csv')).toBe(
      'time-report-my-project-2026-09-01_2026-09-30.csv',
    )
  })

  it('понимает и простую форму filename="..."', () => {
    expect(filenameFromContentDisposition('attachment; filename="hours.csv"', 'report.csv')).toBe('hours.csv')
  })

  it('раскодирует процентную кодировку', () => {
    const header = "attachment; filename*=UTF-8''%D1%87%D0%B0%D1%81%D1%8B.csv"
    expect(filenameFromContentDisposition(header, 'report.csv')).toBe('часы.csv')
  })

  // Заголовок не входит в число «безопасных» CORS-заголовков: стоит убрать его из
  // exposedHeaders на бэкенде — и здесь окажется undefined. Файл всё равно должен
  // скачаться, просто с запасным именем.
  it('без заголовка и на битой кодировке отдаёт запасное имя', () => {
    expect(filenameFromContentDisposition(undefined, 'report.csv')).toBe('report.csv')
    expect(filenameFromContentDisposition("attachment; filename*=UTF-8''%E0%A4%A", 'report.csv')).toBe('report.csv')
  })
})
