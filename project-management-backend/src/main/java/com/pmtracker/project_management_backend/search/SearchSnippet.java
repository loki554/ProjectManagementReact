package com.pmtracker.project_management_backend.search;

/**
 * Разметка найденных слов внутри сниппета.
 *
 * <p>Штатный способ подсветки в Postgres — отдать {@code ts_headline} параметры
 * {@code StartSel=<mark>, StopSel=</mark>} и вставить результат как HTML. Так делать
 * нельзя: {@code ts_headline} не экранирует исходный текст, а исходный текст здесь —
 * описание задачи и комментарий, то есть данные пользователя. Строка
 * {@code <img onerror=…>} в описании приехала бы на фронтенд готовым XSS, который
 * оставалось бы только отрендерить через dangerouslySetInnerHTML.
 *
 * <p>Поэтому границы совпадения помечаются управляющими символами SOH/STX: в осмысленном
 * тексте их не бывает, JSON их переносит, а фронтенд режет сниппет по ним и рисует
 * {@code <mark>} обычными узлами React — без единого места, где строка от пользователя
 * трактуется как разметка. Те же два символа продублированы в
 * {@code project-management-react/src/lib/searchSnippet.jsx}.
 */
public final class SearchSnippet {

    /** Начало подсвеченного фрагмента (U+0001, SOH). */
    public static final String START_MARKER = "\u0001";

    /** Конец подсвеченного фрагмента (U+0002, STX). */
    public static final String END_MARKER = "\u0002";

    /**
     * Параметры ts_headline. MaxFragments=2 — сниппет собирается из двух кусков вокруг
     * совпадений, а не из начала текста; если совпадений в теле нет вовсе (нашлось только
     * название задачи), ts_headline вернёт первые MinWords слов, и это ровно то, что нужно
     * показать под заголовком.
     */
    static final String HEADLINE_OPTIONS = "StartSel=\"" + START_MARKER + "\""
            + ", StopSel=\"" + END_MARKER + "\""
            + ", MaxWords=28, MinWords=12, ShortWord=3"
            + ", MaxFragments=2, FragmentDelimiter=\" … \"";

    private SearchSnippet() {
    }
}
