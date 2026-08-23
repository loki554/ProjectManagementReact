package com.pmtracker.project_management_backend.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

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
}
