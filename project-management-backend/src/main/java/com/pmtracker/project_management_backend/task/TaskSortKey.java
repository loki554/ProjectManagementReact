package com.pmtracker.project_management_backend.task;

import java.util.List;

/**
 * Разрешённые способы сортировки списка задач проекта (3.3). Enum, а не свободная строка:
 * значение приходит в query-параметре и подставляется в ORDER BY как есть, поэтому список
 * должен быть закрытым. Неизвестное значение Spring отсекает сам — MethodArgumentTypeMismatch
 * превращается в 400 в GlobalExceptionHandler, до сервиса запрос не доходит.
 *
 * <p>Раньше сортировка жила на фронте (COMPARATORS в ProjectTaskListPage) и работала по
 * уже загруженному массиву. С пагинацией так нельзя: отсортировать можно только то, что
 * целиком лежит на сервере, иначе «первая страница по названию» окажется первой страницей
 * по position, отсортированной по названию внутри себя.
 */
public enum TaskSortKey {

    NUMBER(List.of("t.taskNumber"), false),
    TITLE(List.of("lower(t.title)"), false),

    // Статус и срочность хранятся строками (@Enumerated(STRING)), поэтому по столбцу они
    // сортируются по алфавиту: DONE, FEEDBACK, IN_PROGRESS... Нужен же порядок жизненного
    // цикла — тот же, в котором идут колонки канбана и пункты селектов на фронте.
    // Он же — порядок объявления констант, отсюда ordinal(); см. enumOrder().
    STATUS(List.of(enumOrder("t.status", TaskStatus.values())), false),
    URGENCY(List.of(enumOrder("t.urgency", TaskUrgency.values())), false),

    // Исполнитель — по фамилии, затем по имени: в интерфейсе он показан именно так,
    // и сортировка по одной фамилии перемешивала бы однофамильцев случайным образом.
    ASSIGNEE(List.of("lower(a.lastName)", "lower(a.firstName)"), true),

    DUE_DATE(List.of("t.dueDate"), true),
    TAG(List.of("lower(tg.name)"), true),
    CATEGORY(List.of("lower(c.name)"), true),

    // Списанные часы — не столбец, а сумма по time_logs. Коррелированный подзапрос, а не
    // group by: с group by пришлось бы перечислять в нём все выбираемые столбцы задачи
    // (включая join fetch), а страница всё равно ограничена LIMIT'ом, то есть подзапрос
    // считается по горстке строк.
    HOURS(List.of("(select coalesce(sum(tl.hours), 0) from TimeLog tl where tl.task.id = t.id)"), false),

    // Порядок карточек внутри колонки канбана; для списка это «как на доске».
    POSITION(List.of("t.position"), false);

    private final List<String> expressions;
    private final boolean nullsLast;

    TaskSortKey(List<String> expressions, boolean nullsLast) {
        this.expressions = expressions;
        this.nullsLast = nullsLast;
    }

    /**
     * Кусок ORDER BY для этого ключа. nulls last навешивается независимо от направления —
     * задачи без исполнителя, срока, тега или категории уезжают в конец и при возрастании,
     * и при убывании (так же вёл себя фронтовый компаратор: пустое значение иначе всплывает
     * наверх и прячет заполненные).
     */
    String toOrderBy(boolean descending) {
        String direction = descending ? "desc" : "asc";
        String suffix = nullsLast ? " nulls last" : "";
        return String.join(", ", expressions.stream().map(e -> e + " " + direction + suffix).toList());
    }

    /**
     * CASE, раскладывающий строковый enum в порядок объявления его констант. Генерируется из
     * values(), а не пишется руками, чтобы новый статус нельзя было добавить в enum и забыть
     * про сортировку — но порядок констант при этом становится значимым, менять его нельзя.
     */
    private static String enumOrder(String path, Enum<?>[] values) {
        StringBuilder sb = new StringBuilder("case ").append(path);
        for (Enum<?> value : values) {
            sb.append(" when ").append(value.getDeclaringClass().getName()).append('.').append(value.name())
                    .append(" then ").append(value.ordinal());
        }
        return sb.append(" else ").append(values.length).append(" end").toString();
    }
}
