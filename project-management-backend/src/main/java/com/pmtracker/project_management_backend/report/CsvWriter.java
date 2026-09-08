package com.pmtracker.project_management_backend.report;

import java.util.List;

/**
 * Сборка CSV по RFC 4180 (4.10).
 *
 * <p><b>Разделитель — запятая, кодировка — UTF-8 с BOM.</b> Пара не самая очевидная, и обе
 * половины выбраны сознательно. Запятая — потому что это и есть CSV: так файл прочитают
 * Google Sheets, LibreOffice, pandas, любой скрипт и мастер импорта самого Excel. Точка с
 * запятой была бы удобнее ровно в одном сценарии — двойной клик по файлу в Excel с русской
 * локалью, где разделителем списка считается «;», — и ценой этому стал бы файл, который
 * больше никто не разбирает по умолчанию. BOM же нужен именно Excel'ю: без него он читает
 * UTF-8 как ANSI, и вместо русских букв получается известная каша. Итог: везде открывается
 * правильно, в Excel с русской локалью — через «Данные → Из текста», где разделитель
 * указывают руками.
 *
 * <p><b>Про формулы.</b> Ячейка, начинающаяся с {@code =}, {@code +}, {@code -} или
 * {@code @}, в Excel и Sheets — не текст, а формула, и это давно известный способ
 * превратить выгрузку в исполняемый код на чужой машине (CSV injection). В отчёт по времени
 * приезжает описание записи, то есть свободный текст, который пишет любой участник проекта,
 * — так что защита здесь не теоретическая. Опасные ячейки предваряются апострофом: он
 * гасит формулу и не виден при чтении файла глазами.
 */
final class CsvWriter {

    /**
     * Признак кодировки для Excel. Явная escape-последовательность, а не символ в исходнике:
     * BOM невидим, и в файле, где его случайно удалили, это никак не проявится до первой
     * выгрузки с кириллицей.
     */
    static final String BOM = "\uFEFF";

    private static final String LINE_SEPARATOR = "\r\n";
    private static final char DELIMITER = ',';

    /** Символы, с которых начинается формула в Excel/Sheets/LibreOffice. */
    private static final String FORMULA_STARTERS = "=+-@\t\r";

    private CsvWriter() {
    }

    /**
     * Собирает таблицу целиком. Строки в памяти, а не потоком: выгрузка ограничена периодом
     * (не больше года, см. TimeReportService), и превратить её в StreamingResponseBody стоит
     * тогда, когда этого потолка перестанет хватать, — а не заранее.
     */
    static String write(List<String> header, List<List<String>> rows) {
        StringBuilder csv = new StringBuilder(BOM);
        appendRow(csv, header);
        for (List<String> row : rows) {
            appendRow(csv, row);
        }
        return csv.toString();
    }

    private static void appendRow(StringBuilder csv, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                csv.append(DELIMITER);
            }
            csv.append(escape(cells.get(i)));
        }
        csv.append(LINE_SEPARATOR);
    }

    /**
     * Пустая ячейка для null — а не строка «null»: в выгрузке отсутствующее описание должно
     * выглядеть отсутствующим.
     */
    private static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        String cell = value;
        if (FORMULA_STARTERS.indexOf(cell.charAt(0)) >= 0) {
            cell = "'" + cell;
        }

        // Кавычки, запятые и переводы строк требуют кавычек вокруг ячейки, а сами кавычки
        // внутри — удвоения (RFC 4180). Перевод строки в описании — обычное дело, поэтому
        // проверка не формальная.
        if (cell.indexOf('"') >= 0 || cell.indexOf(DELIMITER) >= 0
                || cell.indexOf('\n') >= 0 || cell.indexOf('\r') >= 0) {
            return '"' + cell.replace("\"", "\"\"") + '"';
        }
        return cell;
    }
}
