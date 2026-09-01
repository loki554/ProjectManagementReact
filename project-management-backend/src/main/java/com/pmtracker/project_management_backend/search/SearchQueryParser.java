package com.pmtracker.project_management_backend.search;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Превращает то, что человек набрал в строке поиска, в аргумент {@code to_tsquery} (4.1).
 *
 * <p>Почему не {@code websearch_to_tsquery}, который для того и сделан, чтобы принимать
 * пользовательский ввод как есть: он не умеет префиксный поиск, а строка поиска в шапке
 * ищет по мере набора. Без префикса «прое» не находит «проект», и выдача появляется только
 * на последней букве слова — для живого поиска это разница между «работает» и «нет».
 *
 * <p>Отсюда ручная сборка запроса: слова через {@code &}, у последнего — {@code :*}.
 * Последнее слово и есть то, которое пользователь ещё дописывает; вешать префикс на все
 * означало бы, что «за» в середине запроса совпадает с половиной базы.
 *
 * <p>Всё, что не буква и не цифра, считается разделителем слов. Это белый список, а не
 * чёрный: перечислять символы синтаксиса tsquery и надеяться, что список полон — плохая
 * ставка, потому что цена промаха здесь не «странная выдача», а исключение из БД, то есть
 * 500 на строку поиска (первая версия этого класса вырезала операторы по списку и потеряла
 * в нём обратный слэш). После замены в лексеме остаются только буквы и цифры, и
 * экранировать в ней уже нечего.
 *
 * <p>Побочный эффект — «auth-service» распадается на два слова, и это ровно то, что нужно:
 * to_tsvector разбирает такие слова так же. Ввод, от которого не осталось ни одного слова,
 * — не ошибка, а пустая выдача: так же ведёт себя ввод из одних стоп-слов, который Postgres
 * отбрасывает сам.
 */
final class SearchQueryParser {

    private static final Pattern NOT_A_WORD_CHARACTER =
            Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}]+");

    /**
     * Потолок на число слов в запросе. Не ради безопасности, а ради предсказуемости: в
     * склеенном через {@code &} запросе каждое слово — ещё один обход GIN-индекса, и
     * вставленная в строку поиска страница текста превратилась бы в запрос на сотни термов.
     */
    private static final int MAX_TERMS = 10;

    private SearchQueryParser() {
    }

    /**
     * @return строка для {@code to_tsquery}, либо null, если искать нечего
     */
    static String toTsQuery(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        List<String> terms = new ArrayList<>();
        for (String word : NOT_A_WORD_CHARACTER.split(raw)) {
            if (!word.isEmpty()) {
                terms.add(word);
            }
            if (terms.size() == MAX_TERMS) {
                break;
            }
        }
        if (terms.isEmpty()) {
            return null;
        }

        StringBuilder tsQuery = new StringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) {
                tsQuery.append(" & ");
            }
            // Лексема в одинарных кавычках: внутри них Postgres разбирает только саму
            // кавычку и обратный слэш, а после NOT_A_WORD_CHARACTER их в терме нет.
            tsQuery.append('\'').append(terms.get(i)).append('\'');
        }
        return tsQuery.append(":*").toString();
    }
}
