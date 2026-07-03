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
}
