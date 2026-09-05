package com.pmtracker.project_management_backend.mail;

import java.util.List;
import java.util.UUID;

public interface MailService {

    void sendVerificationEmail(String toEmail, UUID token);

    void sendPasswordResetEmail(String toEmail, String token);

    void sendAccountAlreadyExistsEmail(String toEmail);

    void sendProjectInvitationEmail(String toEmail, String token, String projectName,
                                    String inviterName, int expiresInDays);

    /** Письмо об одном уведомлении — мгновенная доставка (4.3). */
    void sendNotificationEmail(String toEmail, NotificationMailItem item, String unsubscribeToken);

    /**
     * Сводка за сутки (4.3). {@code totalCount} — сколько уведомлений накопилось всего;
     * {@code items} может быть короче, если их слишком много для одного письма.
     */
    void sendNotificationDigestEmail(String toEmail, List<NotificationMailItem> items,
                                     int totalCount, String unsubscribeToken);
}
