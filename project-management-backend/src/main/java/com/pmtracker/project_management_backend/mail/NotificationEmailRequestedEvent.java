package com.pmtracker.project_management_backend.mail;

/**
 * «Про это уведомление нужно написать письмо прямо сейчас» — мгновенная доставка (4.3,
 * {@code NotificationDeliveryMode.INSTANT}). Публикуется внутри транзакции, создавшей
 * уведомление, обрабатывается после её коммита — см. {@link MailDispatcher}.
 * <p>
 * Как и у остальных событий пакета, внутри готовые значения: адрес, текстовые куски и
 * токен отписки, посчитанный вызывающим (см. {@link UnsubscribeTokenService}).
 */
public record NotificationEmailRequestedEvent(
        String email,
        NotificationMailItem item,
        String unsubscribeToken
) {
}
