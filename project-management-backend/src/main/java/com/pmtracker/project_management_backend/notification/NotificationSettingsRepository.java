package com.pmtracker.project_management_backend.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationSettingsRepository extends JpaRepository<NotificationSettings, UUID> {

    /**
     * Кому вечером собирать сводку (4.3, {@code NotificationDigestJob}).
     * <p>
     * Выборка идёт от настроек, а не от уведомлений, и это дёшево: строка здесь есть только
     * у тех, кто хоть раз менял настройки, а DAILY_DIGEST — не значение по умолчанию, то есть
     * выбравших его заведомо немного. Возвращаются сущности, а не id: джобу нужны ещё и флаги
     * типов, чтобы не написать человеку про то, от чего он отписался.
     */
    List<NotificationSettings> findByModeAndEmailEnabledTrue(NotificationDeliveryMode mode);
}
