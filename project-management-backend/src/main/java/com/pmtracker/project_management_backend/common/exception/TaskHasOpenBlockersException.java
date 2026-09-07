package com.pmtracker.project_management_backend.common.exception;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Задачу переводят в DONE, а её блокеры ещё не закрыты (4.8).
 *
 * <p>Это предупреждение, а не запрет: тот же запрос с {@code ignoreBlockers: true} проходит.
 * Смысл отказа — в том, чтобы человек увидел препятствие ровно в тот момент, когда он его
 * не видит; настаивать после этого он вправе, потому что про свою работу знает больше
 * трекера (блокер закрыт вне системы, связь оказалась лишней, задачу закрывают частично).
 *
 * <p>Номера уезжают в сообщение, хотя интерфейс подставляет свой текст по коду ошибки: у
 * API есть и другие потребители (curl, будущая интеграция), и «нельзя, есть блокеры» без
 * единого номера отправляет их искать препятствие руками.
 *
 * <p>Двух конструкторов нет — есть две фабрики, потому что перечисляются в них разные
 * вещи. Одиночная правка называет блокеры («что мешает этой задаче»), массовая — сами
 * заблокированные задачи («какие из двадцати выбранных закрывать рано»): на двадцати
 * задачах список чужих блокеров нечитаем, а вопрос там другой. Разница видна только в
 * тексте, поэтому она в него и вынесена — иначе сообщение врало бы в половине случаев.
 */
public class TaskHasOpenBlockersException extends RuntimeException {

    private TaskHasOpenBlockersException(String message) {
        super(message);
    }

    /** Одна задача: перечисляем то, что ей мешает. */
    public static TaskHasOpenBlockersException blockedBy(List<Integer> blockerNumbers) {
        return new TaskHasOpenBlockersException("The task is still blocked by " + format(blockerNumbers));
    }

    /** Массовая правка: перечисляем сами задачи, которые закрывать рано. */
    public static TaskHasOpenBlockersException blockedTasks(List<Integer> taskNumbers) {
        return new TaskHasOpenBlockersException("These tasks still have open blockers: " + format(taskNumbers));
    }

    private static String format(List<Integer> numbers) {
        return numbers.stream().map(number -> "#" + number).collect(Collectors.joining(", "));
    }
}
