package com.pmtracker.project_management_backend.mail;

/**
 * «Приглашение в проект нужно отправить вот на этот адрес» (4.2). Публикуется внутри
 * транзакции, создавшей приглашение, обрабатывается после её коммита — см. {@link MailDispatcher}.
 * <p>
 * Как и у сброса пароля, здесь единственное место, где сырое значение токена живёт после
 * генерации: в БД лежит только SHA-256. Логировать событие целиком нельзя по той же причине.
 * <p>
 * Имена проекта и пригласившего едут значениями, а не сущностями: слушатель работает в
 * другом потоке и вне транзакции, где ленивые связи JPA уже не подгрузить.
 */
public record ProjectInvitationEmailRequestedEvent(
        String email,
        String token,
        String projectName,
        String inviterName,
        int expiresInDays
) {
}
