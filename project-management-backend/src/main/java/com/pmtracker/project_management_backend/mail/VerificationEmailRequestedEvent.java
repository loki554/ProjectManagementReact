package com.pmtracker.project_management_backend.mail;

import java.util.UUID;

/**
 * «Письмо с подтверждением нужно отправить вот на этот адрес». Публикуется внутри транзакции,
 * которая создала токен (регистрация или повторная отправка), а обрабатывается уже после её
 * коммита — см. {@link VerificationMailDispatcher}.
 * <p>
 * Событие несёт готовые значения, а не сущности: слушатель работает в другом потоке и вне
 * транзакции, где ленивые связи JPA уже не подгрузить.
 */
public record VerificationEmailRequestedEvent(String email, UUID token) {
}
