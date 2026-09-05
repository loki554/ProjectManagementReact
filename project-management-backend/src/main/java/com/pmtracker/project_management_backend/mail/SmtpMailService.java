package com.pmtracker.project_management_backend.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class SmtpMailService implements MailService {

    private final JavaMailSender mailSender;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    public SmtpMailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public void sendVerificationEmail(String toEmail, UUID token) {
        String link = frontendBaseUrl + "/verify-email?token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(toEmail);
        message.setSubject("Подтверждение регистрации — Task Tracker");
        message.setText("""
                Здравствуйте!

                Для подтверждения регистрации перейдите по ссылке:
                %s

                Ссылка действительна 24 часа. Если вы не регистрировались — просто проигнорируйте это письмо.
                """.formatted(link));

        mailSender.send(message);
    }

    @Override
    public void sendPasswordResetEmail(String toEmail, String token) {
        String link = frontendBaseUrl + "/reset-password?token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(toEmail);
        message.setSubject("Восстановление пароля — Task Tracker");
        // Последний абзац здесь не вежливость, а часть защиты: запросить сброс на чужой адрес
        // может кто угодно, и человек, такого письма не ждавший, должен понимать, что делать
        // (ничего) и что его аккаунт цел, пока по ссылке не перешли.
        message.setText("""
                Здравствуйте!

                Кто-то запросил смену пароля для аккаунта с этим адресом. Чтобы задать новый пароль,
                перейдите по ссылке:
                %s

                Ссылка действительна 1 час и сработает только один раз.

                Если вы этого не запрашивали — просто проигнорируйте письмо: пароль останется прежним,
                и никаких действий с аккаунтом не произойдёт.
                """.formatted(link));

        mailSender.send(message);
    }

    /**
     * Уходит вместо письма с подтверждением, когда регистрируются на уже занятый адрес.
     * Про сам аккаунт (кем, когда заведён, подтверждён ли) не говорит ничего: письмо может
     * прочитать не только владелец — адрес мог быть введён по ошибке или намеренно чужой.
     */
    @Override
    public void sendAccountAlreadyExistsEmail(String toEmail) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(toEmail);
        message.setSubject("Попытка регистрации — Task Tracker");
        message.setText("""
                Здравствуйте!

                Кто-то попытался зарегистрироваться в Task Tracker с этим адресом, но аккаунт с ним
                уже существует. Нового аккаунта не создано, и ничего в существующем не изменилось.

                Если это были вы — просто войдите: %s/login
                Забыли пароль — здесь можно задать новый: %s/forgot-password

                Если вы ничего не делали, ничего делать и не нужно: без пароля и без доступа к этому
                ящику войти в аккаунт невозможно.
                """.formatted(frontendBaseUrl, frontendBaseUrl));

        mailSender.send(message);
    }

    /**
     * Приглашение в проект (4.2). Уходит только тому, у кого аккаунта ещё нет: у
     * зарегистрированного приглашение письма не требует — он добавляется в проект сразу.
     * <p>
     * Письмо намеренно не выглядит кнопкой «войти»: перейдя по ссылке, человек попадает на
     * страницу приглашения, где сам решает, заводить аккаунт или нет. Последний абзац — та
     * же роль, что у письма о сбросе пароля: адрес мог быть введён по ошибке, и получатель
     * должен понимать, что с ним пока ничего не произошло.
     * <p>
     * В тему письма подставляется только название проекта — оно ограничено печатаемой
     * латиницей (см. CreateProjectRequest), то есть перевода строки, которым можно было бы
     * дописать заголовок письма, в нём не бывает. Имя пригласившего таких ограничений не
     * имеет и поэтому стоит только в теле.
     */
    @Override
    public void sendProjectInvitationEmail(String toEmail, String token, String projectName,
                                           String inviterName, int expiresInDays) {
        String link = frontendBaseUrl + "/invite?token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(toEmail);
        message.setSubject("Приглашение в проект «" + projectName + "» — Task Tracker");
        message.setText("""
                Здравствуйте!

                %s приглашает вас в проект «%s» в Task Tracker.

                Чтобы принять приглашение, перейдите по ссылке:
                %s

                Ссылка действительна %d дней. Если аккаунта у вас ещё нет, его можно завести на
                той же странице — после подтверждения адреса вы окажетесь в проекте автоматически.

                Если вы не знаете отправителя — просто проигнорируйте письмо: пока по ссылке
                не перешли, ничего не произошло, и никакого аккаунта на ваш адрес не заведено.
                """.formatted(inviterName, projectName, link, expiresInDays));

        mailSender.send(message);
    }

    /**
     * Письмо об одном уведомлении — мгновенная доставка (4.3).
     * <p>
     * Первое письмо приложения, которого человек сам не запрашивал: остальные четыре уходят
     * в ответ на действие (регистрация, сброс пароля, приглашение). Отсюда две вещи, которых
     * у них нет, — ссылка отписки в каждом письме (см. {@link NotificationMailTexts#footer})
     * и настройки, решающие, отправлять ли его вообще (см. {@code NotificationSettings}).
     * <p>
     * В теме письма стоит заголовок задачи — единственный пользовательский текст, попадающий
     * у нас в заголовок письма; чем это опасно и что с этим делается, написано в
     * {@link NotificationMailTexts#sanitizeHeaderValue}.
     */
    @Override
    public void sendNotificationEmail(String toEmail, NotificationMailItem item, String unsubscribeToken) {
        StringBuilder text = new StringBuilder("Здравствуйте!\n\n");
        text.append(NotificationMailTexts.line(item)).append('\n');

        String context = NotificationMailTexts.context(item);
        if (!context.isEmpty()) {
            text.append(context).append('\n');
        }

        String link = NotificationMailTexts.taskLink(frontendBaseUrl, item);
        if (link != null) {
            text.append('\n').append("Открыть задачу: ").append(link).append('\n');
        }
        text.append(NotificationMailTexts.footer(frontendBaseUrl, unsubscribeToken));

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(toEmail);
        message.setSubject(NotificationMailTexts.subject(item));
        message.setText(text.toString());

        mailSender.send(message);
    }

    /**
     * Сводка за сутки (4.3, режим DAILY_DIGEST).
     * <p>
     * Одно письмо вместо десяти — весь смысл режима, поэтому и оформление другое: не «событие
     * и ссылка», а список, где у каждой строки своя ссылка. Заголовок задачи в тему не
     * попадает вовсе — сводка не про одну задачу, и подставлять туда первую попавшуюся
     * значило бы врать темой письма.
     */
    @Override
    public void sendNotificationDigestEmail(String toEmail, List<NotificationMailItem> items,
                                            int totalCount, String unsubscribeToken) {
        StringBuilder text = new StringBuilder("Здравствуйте!\n\nЗа последние сутки:\n\n");
        for (NotificationMailItem item : items) {
            text.append("• ").append(NotificationMailTexts.line(item)).append('\n');
            String context = NotificationMailTexts.context(item);
            if (!context.isEmpty()) {
                text.append("  ").append(context).append('\n');
            }
            String link = NotificationMailTexts.taskLink(frontendBaseUrl, item);
            if (link != null) {
                text.append("  ").append(link).append('\n');
            }
            text.append('\n');
        }
        // Хвост «и ещё N» появляется, когда накопилось больше, чем помещается в одно письмо:
        // отметку «отправлено» получают все уведомления сводки, поэтому сюда они больше
        // не вернутся, и умолчать о них нельзя (см. NotificationDigestJob.MAX_ITEMS_PER_EMAIL).
        int hidden = totalCount - items.size();
        if (hidden > 0) {
            text.append("…и ещё ").append(hidden).append(" — целиком видно в приложении: ")
                    .append(frontendBaseUrl).append("/projects").append('\n');
        }
        text.append(NotificationMailTexts.footer(frontendBaseUrl, unsubscribeToken));

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(toEmail);
        message.setSubject(NotificationMailTexts.digestSubject(totalCount));
        message.setText(text.toString());

        mailSender.send(message);
    }
}
