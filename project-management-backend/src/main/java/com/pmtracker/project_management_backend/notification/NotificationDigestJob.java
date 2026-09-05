package com.pmtracker.project_management_backend.notification;

import com.pmtracker.project_management_backend.common.scheduling.SchedulerLock;
import com.pmtracker.project_management_backend.common.scheduling.SchedulerLockKey;
import com.pmtracker.project_management_backend.mail.NotificationDigestEmailRequestedEvent;
import com.pmtracker.project_management_backend.mail.NotificationMailItem;
import com.pmtracker.project_management_backend.mail.UnsubscribeTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Суточная сводка уведомлений почтой — режим {@link NotificationDeliveryMode#DAILY_DIGEST} (4.3).
 * <p>
 * Смысл режима ровно один: одно письмо вместо десяти. Поэтому джоб не «досылает то, что не
 * ушло мгновенно», а обслуживает только тех, кто дайджест выбрал: у остальных письмо уже
 * ушло в момент события и {@code email_sent_at} у их уведомлений проставлен.
 * <p>
 * В отличие от уборок (токены, корзина, файлы-сироты), этот джоб НЕ идемпотентен и требует
 * блокировки между инстансами: он читает «о чём ещё не писали» и пишет отметку, то есть два
 * одновременных прогона отправят человеку две одинаковые сводки — ровно та же гонка, что у
 * {@code NotificationScheduler} (см. {@link SchedulerLock}). Повторный запуск после
 * успешного, наоборот, безопасен: отметки уже стоят, собирать нечего.
 */
@Component
public class NotificationDigestJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationDigestJob.class);

    /**
     * Насколько назад смотрит сводка. Сутки плюс запас: пропущенный прогон (перевыкат,
     * упавший инстанс) не должен означать, что за тот день человеку не написали вовсе.
     * Верхняя граница нужна не меньше нижней — без неё первый же дайджест у человека,
     * только что переключившего режим, стал бы письмом на всю его историю уведомлений.
     */
    static final Duration WINDOW = Duration.ofHours(48);

    /**
     * Сколько событий печатается в письме. Остальное сворачивается в «и ещё N» — но отметку
     * «отправлено» получают ВСЕ вошедшие в сводку, иначе завтрашнее письмо начиналось бы с
     * позавчерашнего хвоста и так по кругу.
     */
    static final int MAX_ITEMS_PER_EMAIL = 30;

    private final NotificationRepository notificationRepository;
    private final NotificationSettingsRepository settingsRepository;
    private final UnsubscribeTokenService unsubscribeTokenService;
    private final SchedulerLock schedulerLock;
    private final ApplicationEventPublisher eventPublisher;

    public NotificationDigestJob(NotificationRepository notificationRepository,
                                 NotificationSettingsRepository settingsRepository,
                                 UnsubscribeTokenService unsubscribeTokenService,
                                 SchedulerLock schedulerLock,
                                 ApplicationEventPublisher eventPublisher) {
        this.notificationRepository = notificationRepository;
        this.settingsRepository = settingsRepository;
        this.unsubscribeTokenService = unsubscribeTokenService;
        this.schedulerLock = schedulerLock;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Утро, а не ночь: сводка «что было вчера» полезна к началу рабочего дня, а не в 4 часа
     * среди уборок. Час вынесен в свойство — часовой пояс у сервера один, а команда может
     * жить в другом (см. app.notifications.digest.cron).
     */
    @Scheduled(cron = "${app.notifications.digest.cron:0 0 9 * * *}")
    @Transactional
    public void sendDigests() {
        if (!schedulerLock.tryAcquire(SchedulerLockKey.NOTIFICATION_DIGEST)) {
            log.debug("Notification digest skipped: another instance is running it");
            return;
        }

        Map<UUID, NotificationSettings> byRecipient =
                settingsRepository.findByModeAndEmailEnabledTrue(NotificationDeliveryMode.DAILY_DIGEST).stream()
                        .collect(Collectors.toMap(NotificationSettings::getUserId, Function.identity()));
        if (byRecipient.isEmpty()) {
            return;
        }

        List<Notification> pending =
                notificationRepository.findPendingForDigest(byRecipient.keySet(), Instant.now().minus(WINDOW));

        // Запрос уже отсортирован по получателю, но группируем явно: порядок строк — свойство
        // запроса, а не контракт, и завязывать на него разбиение по письмам не стоит.
        Map<UUID, List<Notification>> grouped = new LinkedHashMap<>();
        for (Notification notification : pending) {
            NotificationSettings settings = byRecipient.get(notification.getRecipient().getId());
            // Тип, от которого человек отписался, в сводку не попадает — и отметку не
            // получает: письма по нему не было.
            //
            // Отсюда следствие, общее для всех «не написали» (выключенный тип, выключенная
            // почта целиком, отписка по ссылке): такие уведомления остаются с пустым
            // email_sent_at, и если человек включит письма обратно, ближайшая сводка
            // покажет накопившееся — но не больше, чем за окно. Проверено вживую: адресат,
            // отписавшийся и затем выбравший дайджест, получил в сводке и то, что пришло,
            // пока он был отписан. Это не дефект, а самое разумное из возможных поведений:
            // «включил и увидел, что пропустил», с потолком в двое суток.
            if (settings != null && settings.allows(notification.getType())) {
                grouped.computeIfAbsent(notification.getRecipient().getId(), id -> new ArrayList<>())
                        .add(notification);
            }
        }

        Instant sentAt = Instant.now();
        for (List<Notification> digest : grouped.values()) {
            List<NotificationMailItem> items = digest.stream()
                    .limit(MAX_ITEMS_PER_EMAIL)
                    .map(n -> NotificationMailItem.from(n, NotificationService.displayName(n.getActor())))
                    .toList();
            // Сущности managed — отметку проставит dirty checking на коммите, отдельного
            // update-запроса на каждую строку писать не нужно.
            digest.forEach(n -> n.setEmailSentAt(sentAt));

            UUID recipientId = digest.getFirst().getRecipient().getId();
            eventPublisher.publishEvent(new NotificationDigestEmailRequestedEvent(
                    digest.getFirst().getRecipient().getEmail(),
                    items,
                    digest.size(),
                    unsubscribeTokenService.tokenFor(recipientId)));
        }

        if (!grouped.isEmpty()) {
            log.info("Notification digest: {} email(s) queued for {} notification(s)",
                    grouped.size(), pending.size());
        }
    }
}
