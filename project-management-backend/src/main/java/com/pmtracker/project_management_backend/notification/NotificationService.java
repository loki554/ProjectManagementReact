package com.pmtracker.project_management_backend.notification;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.dto.PageResponse;
import com.pmtracker.project_management_backend.common.exception.NotificationNotFoundException;
import com.pmtracker.project_management_backend.mail.NotificationEmailRequestedEvent;
import com.pmtracker.project_management_backend.mail.NotificationMailItem;
import com.pmtracker.project_management_backend.mail.UnsubscribeTokenService;
import com.pmtracker.project_management_backend.notification.dto.NotificationResponse;
import com.pmtracker.project_management_backend.task.Task;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Уведомления пользователя (колокольчик в хедере). Событийные типы (task_assigned,
 * task_comment) пишутся напрямую из TaskService/TaskCommentService, в той же транзакции,
 * что и само действие — тот же приём, что ActivityService.record (см. её комментарий).
 * Типы task_due_soon/task_overdue не событийные, а вычисляемые по расписанию — их
 * создаёт NotificationScheduler.
 * <p>
 * С 4.3 у уведомления есть второй канал доставки — почта. Решение «писать ли письмо и когда»
 * принимается здесь же, в той же транзакции: настройки получателя читаются один раз на
 * уведомление, и результат фиксируется в самой строке (email_sent_at), а не пересчитывается
 * потом. Иначе рассылке пришлось бы гадать, что человек хотел вчера, когда уведомление
 * создавалось, — а между созданием и вечерним дайджестом настройки вполне могли поменяться.
 */
@Service
public class NotificationService {

    private static final int PAGE_SIZE = 20;
    // Превью текста комментария в уведомлении — сам комментарий читается на странице задачи.
    private static final int COMMENT_EXCERPT_MAX_LENGTH = 140;

    public static final String TYPE_TASK_ASSIGNED = "task_assigned";
    public static final String TYPE_TASK_COMMENT = "task_comment";
    public static final String TYPE_TASK_DUE_SOON = "task_due_soon";
    public static final String TYPE_TASK_OVERDUE = "task_overdue";

    private final NotificationRepository notificationRepository;
    private final NotificationSettingsRepository settingsRepository;
    private final UnsubscribeTokenService unsubscribeTokenService;
    private final ApplicationEventPublisher eventPublisher;

    public NotificationService(NotificationRepository notificationRepository,
                               NotificationSettingsRepository settingsRepository,
                               UnsubscribeTokenService unsubscribeTokenService,
                               ApplicationEventPublisher eventPublisher) {
        this.notificationRepository = notificationRepository;
        this.settingsRepository = settingsRepository;
        this.unsubscribeTokenService = unsubscribeTokenService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void notifyTaskAssigned(Task task, User actor, User recipient) {
        // Не уведомляем о самоназначении и не шлём уведомление удалённому исполнителю (null).
        if (recipient == null || recipient.getId().equals(actor.getId())) {
            return;
        }
        create(recipient, actor, TYPE_TASK_ASSIGNED, task, basePayload(task));
    }

    @Transactional
    public void notifyTaskComment(Task task, User actor, String commentBody) {
        Map<String, Object> payload = basePayload(task);
        payload.put("commentExcerpt", excerpt(commentBody));

        // Постановщик и исполнитель уведомляются оба, но: не сам автор комментария,
        // и не дважды одному человеку, если он и постановщик, и исполнитель одновременно.
        //
        // Arrays.asList, а не List.of: исполнителя у задачи может не быть, а List.of падает
        // NPE на null-элементе — то есть любой комментарий к неназначенной задаче (а это
        // состояние по умолчанию для только что созданной) возвращал 500 вместо 201. Проверка
        // recipient == null ниже как раз и написана в расчёте на отсутствующего исполнителя.
        Set<UUID> notifiedUserIds = new HashSet<>();
        for (User recipient : Arrays.asList(task.getCreatedBy(), task.getAssignee())) {
            if (recipient == null || recipient.getId().equals(actor.getId()) || !notifiedUserIds.add(recipient.getId())) {
                continue;
            }
            create(recipient, actor, TYPE_TASK_COMMENT, task, payload);
        }
    }

    @Transactional
    public boolean notifyDueSoonIfNeeded(Task task) {
        return notifySystemAlertIfNeeded(task, TYPE_TASK_DUE_SOON);
    }

    @Transactional
    public boolean notifyOverdueIfNeeded(Task task) {
        return notifySystemAlertIfNeeded(task, TYPE_TASK_OVERDUE);
    }

    private boolean notifySystemAlertIfNeeded(Task task, String type) {
        User recipient = task.getAssignee();
        if (recipient == null) {
            return false;
        }
        if (notificationRepository.existsByRecipientIdAndTaskIdAndType(recipient.getId(), task.getId(), type)) {
            return false;
        }
        Map<String, Object> payload = basePayload(task);
        payload.put("dueDate", task.getDueDate() != null ? task.getDueDate().toString() : null);
        create(recipient, null, type, task, payload);
        return true;
    }

    private void create(User recipient, User actor, String type, Task task, Map<String, Object> payload) {
        Notification notification = new Notification();
        notification.setRecipient(recipient);
        notification.setActor(actor);
        notification.setType(type);
        notification.setTask(task);
        notification.setPayload(payload);
        notificationRepository.save(notification);
        planEmail(notification, recipient, actor);
    }

    /**
     * Решает судьбу письма по только что созданному уведомлению (4.3). Три исхода:
     * <ul>
     *   <li>почта выключена целиком или выключен этот тип — письма не будет, и
     *       {@code emailSentAt} остаётся null: это «не отправляли», а не «отправим потом»;</li>
     *   <li>режим DAILY_DIGEST — тоже null, но вечером строку заберёт
     *       {@link NotificationDigestJob} и отметит уже он;</li>
     *   <li>режим INSTANT (он же поведение по умолчанию, когда настроек нет вовсе) —
     *       отметка ставится прямо сейчас, а письмо уходит после коммита.</li>
     * </ul>
     * «Отправлено» здесь означает «передано MailDispatcher»: ретраи и дальнейшая судьба
     * письма — его дело, и повторять их вечером сводкой было бы вторым письмом об одном
     * и том же в самом частом случае — когда первое как раз дошло.
     * <p>
     * Событие публикуется внутри транзакции, а уходит после коммита: письмо со ссылкой на
     * задачу не должно опережать саму задачу (см. MailDispatcher).
     */
    private void planEmail(Notification notification, User recipient, User actor) {
        NotificationSettings settings = settingsRepository.findById(recipient.getId()).orElse(null);
        if (settings != null
                && (!settings.isEmailEnabled() || !settings.allows(notification.getType()))) {
            return;
        }
        if (settings != null && settings.getMode() == NotificationDeliveryMode.DAILY_DIGEST) {
            return;
        }

        notification.setEmailSentAt(Instant.now());
        eventPublisher.publishEvent(new NotificationEmailRequestedEvent(
                recipient.getEmail(),
                NotificationMailItem.from(notification, displayName(actor)),
                unsubscribeTokenService.tokenFor(recipient.getId())));
    }

    /** null у системных уведомлений: их создаёт планировщик, действующего лица там нет. */
    static String displayName(User user) {
        return user != null ? user.getLastName() + " " + user.getFirstName() : null;
    }

    // Дедлайн задачи сдвинулся или задача больше не активна (DONE/REJECTED) — старые
    // "скоро истекает"/"просрочена" стали неверными, чистим их (см. TaskService).
    @Transactional
    public void clearDueDateAlerts(UUID taskId) {
        notificationRepository.deleteDueDateAlerts(taskId);
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(User currentUser, int page) {
        var pageRequest = PageRequest.of(Math.max(page, 0), PAGE_SIZE);
        var notificationPage = notificationRepository.findByRecipientIdOrderByCreatedAtDesc(currentUser.getId(), pageRequest);
        return PageResponse.from(notificationPage.map(NotificationResponse::from));
    }

    @Transactional(readOnly = true)
    public long unreadCount(User currentUser) {
        return notificationRepository.countByRecipientIdAndReadAtIsNull(currentUser.getId());
    }

    @Transactional
    public void markRead(User currentUser, UUID notificationId) {
        Notification notification = notificationRepository.findByIdAndRecipientId(notificationId, currentUser.getId())
                .orElseThrow(NotificationNotFoundException::new);
        if (notification.getReadAt() == null) {
            notification.setReadAt(Instant.now());
        }
    }

    @Transactional
    public void markAllRead(User currentUser) {
        notificationRepository.markAllRead(currentUser.getId(), Instant.now());
    }

    private Map<String, Object> basePayload(Task task) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskNumber", task.getTaskNumber());
        payload.put("title", task.getTitle());
        payload.put("projectSlug", task.getProject().getSlug());
        payload.put("projectName", task.getProject().getName());
        return payload;
    }

    private String excerpt(String body) {
        if (body == null) {
            return null;
        }
        String trimmed = body.strip();
        return trimmed.length() > COMMENT_EXCERPT_MAX_LENGTH
                ? trimmed.substring(0, COMMENT_EXCERPT_MAX_LENGTH) + "…"
                : trimmed;
    }
}
