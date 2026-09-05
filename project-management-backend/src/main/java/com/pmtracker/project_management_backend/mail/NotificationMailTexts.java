package com.pmtracker.project_management_backend.mail;

import com.pmtracker.project_management_backend.notification.NotificationService;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Тексты писем об уведомлениях (4.3): тема, строчка про событие, ссылка на задачу.
 * <p>
 * Вынесено из {@code SmtpMailService} отдельно по двум причинам. Во-первых, мгновенное письмо
 * и сводка пишут одно и то же разными словами вокруг — общей должна быть именно формулировка
 * события, иначе «назначил(а) вам задачу» в этих двух письмах разъедется. Во-вторых, здесь
 * живёт единственное место, где пользовательский текст попадает в ЗАГОЛОВОК письма, и такое
 * место лучше иметь одно и с тестами (см. {@link #sanitizeHeaderValue}).
 * <p>
 * Язык — русский, как и у остальных писем: backend i18n сознательно вне скоупа (см. Decisions
 * Log в IMPLEMENTATION_PLAN.md), фронтенд переводит сам по кодам.
 */
final class NotificationMailTexts {

    /**
     * Длина куска пользовательского текста в теме. Почтовые клиенты всё равно обрезают тему
     * примерно здесь, а тащить в заголовок письма многокилобайтный заголовок задачи незачем.
     */
    private static final int SUBJECT_TEXT_MAX_LENGTH = 80;

    /**
     * Дата в письме — в UTC и с явной пометкой. Своего часового пояса у пользователя в
     * профиле нет (и не заведено ради одной строчки в письме), а показать местное время
     * сервера значило бы соврать: у получателя оно другое, и понять это по письму нельзя.
     */
    private static final DateTimeFormatter DUE_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);

    private NotificationMailTexts() {
    }

    static String subject(NotificationMailItem item) {
        String title = sanitizeHeaderValue(item.stringValue("title"));
        return switch (item.type()) {
            case NotificationService.TYPE_TASK_ASSIGNED -> "Вам назначена задача «" + title + "» — Task Tracker";
            case NotificationService.TYPE_TASK_COMMENT -> "Новый комментарий к задаче «" + title + "» — Task Tracker";
            case NotificationService.TYPE_TASK_DUE_SOON -> "Скоро истекает срок задачи «" + title + "» — Task Tracker";
            case NotificationService.TYPE_TASK_OVERDUE -> "Просрочена задача «" + title + "» — Task Tracker";
            // Новый тип уведомления доходит до почты и без правки этого файла — нейтральной
            // темой, но с полноценным телом письма. Молча не отправить было бы хуже.
            default -> "Новое уведомление — Task Tracker";
        };
    }

    static String digestSubject(int totalCount) {
        return "Уведомления за сутки (" + totalCount + ") — Task Tracker";
    }

    /**
     * Строчка про событие — то же самое, что показывает колокольчик (ключи
     * {@code notifications.types.*} в локалях фронтенда). Имя действующего лица уже внутри:
     * в письме его неоткуда взять глазами, в отличие от списка, где оно стоит отдельной
     * жирной частью строки.
     */
    static String line(NotificationMailItem item) {
        String actor = item.actorName() != null ? item.actorName() : "Кто-то";
        String title = orEmpty(item.stringValue("title"));
        return switch (item.type()) {
            case NotificationService.TYPE_TASK_ASSIGNED ->
                    actor + " назначил(а) вам задачу «" + title + "»";
            case NotificationService.TYPE_TASK_COMMENT ->
                    actor + " прокомментировал(а) задачу «" + title + "»: «"
                            + orEmpty(item.stringValue("commentExcerpt")) + "»";
            case NotificationService.TYPE_TASK_DUE_SOON ->
                    "Скоро истекает срок задачи «" + title + "»" + dueDateSuffix(item);
            case NotificationService.TYPE_TASK_OVERDUE ->
                    "Истёк срок задачи «" + title + "»" + dueDateSuffix(item);
            default -> "Новое уведомление по задаче «" + title + "»";
        };
    }

    /** Проект и номер задачи — то, чего в строке события нет, а в письме без контекста не хватает. */
    static String context(NotificationMailItem item) {
        String project = item.stringValue("projectName");
        String taskNumber = item.stringValue("taskNumber");
        if (project == null) {
            return "";
        }
        return taskNumber != null ? "Проект «" + project + "», задача #" + taskNumber
                : "Проект «" + project + "»";
    }

    /**
     * Ссылка на задачу или null, если её не собрать. null здесь не дефект данных: снапшот
     * payload пишется на момент события и в принципе может оказаться неполным у уведомления
     * нового типа — письмо без ссылки всё равно читаемо.
     */
    static String taskLink(String frontendBaseUrl, NotificationMailItem item) {
        String projectSlug = item.stringValue("projectSlug");
        String taskNumber = item.stringValue("taskNumber");
        if (projectSlug == null || taskNumber == null) {
            return null;
        }
        return frontendBaseUrl + "/projects/" + projectSlug + "/tasks/" + taskNumber;
    }

    /**
     * Хвост письма со ссылкой отписки. Обязателен на каждом письме об уведомлениях (4.3):
     * человек, которому это не нужно, должен уметь прекратить рассылку из самого письма, а
     * не искать настройку в интерфейсе, куда он как раз и не заходит.
     * <p>
     * Ссылок две, потому что и решения два: «совсем не писать» и «писать по-другому».
     */
    static String footer(String frontendBaseUrl, String unsubscribeToken) {
        return """

                —
                Настроить, о чём и как часто вам писать: %s/profile
                Отписаться от всех писем-уведомлений: %s/unsubscribe?token=%s
                """.formatted(frontendBaseUrl, frontendBaseUrl, unsubscribeToken);
    }

    /**
     * Готовит пользовательский текст к подстановке в ТЕМУ письма.
     * <p>
     * Заголовок задачи, в отличие от названия проекта, ничем не ограничен: в нём бывают
     * переводы строки, и подставленный в тему как есть он позволил бы дописать письму
     * собственные заголовки (классический header injection). JavaMail кодирует тему в
     * RFC 2047 и на не-ASCII, скорее всего, спас бы сам, но полагаться на побочный эффект
     * кодировки в такой роли нельзя — вычищаем управляющие символы явно.
     * <p>
     * Заодно схлопываем пробелы и обрезаем длину: тема письма — это одна короткая строка.
     */
    static String sanitizeHeaderValue(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
        return cleaned.length() > SUBJECT_TEXT_MAX_LENGTH
                ? cleaned.substring(0, SUBJECT_TEXT_MAX_LENGTH) + "…"
                : cleaned;
    }

    private static String dueDateSuffix(NotificationMailItem item) {
        String dueDate = item.stringValue("dueDate");
        if (dueDate == null) {
            return "";
        }
        try {
            return " (до " + DUE_DATE_FORMAT.format(Instant.parse(dueDate)) + " UTC)";
        } catch (RuntimeException e) {
            // Снапшот payload пишется кодом, а не человеком, но письмо не то место, где
            // стоит падать из-за неразобранной даты: без неё строчка остаётся осмысленной.
            return "";
        }
    }

    private static String orEmpty(String value) {
        return value != null ? value : "";
    }
}
