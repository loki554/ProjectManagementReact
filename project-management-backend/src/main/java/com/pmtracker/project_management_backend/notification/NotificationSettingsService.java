package com.pmtracker.project_management_backend.notification;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.UserRepository;
import com.pmtracker.project_management_backend.common.exception.InvalidOrExpiredTokenException;
import com.pmtracker.project_management_backend.mail.UnsubscribeTokenService;
import com.pmtracker.project_management_backend.notification.dto.NotificationSettingsResponse;
import com.pmtracker.project_management_backend.notification.dto.UpdateNotificationSettingsRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Настройки email-уведомлений: чтение, изменение и отписка по ссылке из письма (4.3).
 * <p>
 * Отсутствие строки в {@code notification_settings} — не пробел в данных, а полноценное
 * состояние «всё по умолчанию»: письма приходят сразу, обо всех четырёх типах. Строка
 * появляется в тот момент, когда человек впервые что-то поменял. Так у выката не возникает
 * вопроса, что делать с уже существующими пользователями, а у регистрации — лишней вставки
 * ради значений, которые и так заданы кодом.
 */
@Service
public class NotificationSettingsService {

    private static final Logger log = LoggerFactory.getLogger(NotificationSettingsService.class);

    private final NotificationSettingsRepository settingsRepository;
    private final UserRepository userRepository;
    private final UnsubscribeTokenService unsubscribeTokenService;

    public NotificationSettingsService(NotificationSettingsRepository settingsRepository,
                                       UserRepository userRepository,
                                       UnsubscribeTokenService unsubscribeTokenService) {
        this.settingsRepository = settingsRepository;
        this.userRepository = userRepository;
        this.unsubscribeTokenService = unsubscribeTokenService;
    }

    @Transactional(readOnly = true)
    public NotificationSettingsResponse get(User currentUser) {
        return NotificationSettingsResponse.from(
                settingsRepository.findById(currentUser.getId()).orElseGet(NotificationSettings::new));
    }

    @Transactional
    public NotificationSettingsResponse update(User currentUser, UpdateNotificationSettingsRequest request) {
        NotificationSettings settings = findOrCreate(currentUser.getId());
        settings.setEmailEnabled(request.emailEnabled());
        settings.setMode(request.mode());
        settings.setTaskAssigned(request.taskAssigned());
        settings.setTaskComment(request.taskComment());
        settings.setTaskMention(request.taskMention());
        settings.setTaskDueSoon(request.taskDueSoon());
        settings.setTaskOverdue(request.taskOverdue());
        settingsRepository.save(settings);
        return NotificationSettingsResponse.from(settings);
    }

    /**
     * Отписка по ссылке из письма. Гасит только главный выключатель: флаги типов и режим
     * остаются как были, чтобы «я передумал» в профиле возвращало прежние настройки, а не
     * заставляло собирать их заново.
     * <p>
     * Токен — подпись, а не строка в базе (см. {@link UnsubscribeTokenService}); он же
     * единственное, что здесь удостоверяет личность: человек, дошедший до этой кнопки, в
     * приложение не входил и, вполне возможно, не помнит от него пароля — требовать логина
     * ради «перестаньте мне писать» значит не дать отписаться вовсе.
     * <p>
     * Удалённый пользователь — не ошибка, а тишина: токен валиден, отписывать некого,
     * отвечаем как при успехе. Разница в ответе означала бы способ проверять по ссылке из
     * старого письма, жив ли ещё аккаунт.
     */
    @Transactional
    public void unsubscribe(String rawToken) {
        UUID userId = unsubscribeTokenService.verify(rawToken).orElseThrow(InvalidOrExpiredTokenException::new);
        if (!userRepository.existsById(userId)) {
            return;
        }
        NotificationSettings settings = findOrCreate(userId);
        settings.setEmailEnabled(false);
        settingsRepository.save(settings);
        log.info("User {} unsubscribed from notification emails via an email link", userId);
    }

    private NotificationSettings findOrCreate(UUID userId) {
        return settingsRepository.findById(userId).orElseGet(() -> {
            NotificationSettings fresh = new NotificationSettings();
            fresh.setUserId(userId);
            return fresh;
        });
    }
}
