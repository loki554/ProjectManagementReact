package com.pmtracker.project_management_backend.mail;

import java.util.List;

/**
 * «Отправить человеку сводку за сутки» — режим {@code NotificationDeliveryMode.DAILY_DIGEST}
 * (4.3). Публикуется из {@code NotificationDigestJob} внутри транзакции, которая помечает
 * вошедшие в сводку уведомления отправленными, и обрабатывается после её коммита.
 * <p>
 * {@code totalCount} может быть больше, чем {@code items.size()}: в письмо попадает не больше
 * фиксированного числа строк, остальное сворачивается в «и ещё N» — письмо на четыреста
 * пунктов никто не читает, а отметку «отправлено» получают все, иначе завтрашняя сводка
 * начиналась бы с позавчерашнего.
 */
public record NotificationDigestEmailRequestedEvent(
        String email,
        List<NotificationMailItem> items,
        int totalCount,
        String unsubscribeToken
) {
}
