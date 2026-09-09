package com.pmtracker.project_management_backend.notification;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.dto.PageResponse;
import com.pmtracker.project_management_backend.common.exception.NotificationNotFoundException;
import com.pmtracker.project_management_backend.mail.NotificationEmailRequestedEvent;
import com.pmtracker.project_management_backend.mail.NotificationMailItem;
import com.pmtracker.project_management_backend.mail.UnsubscribeTokenService;
import com.pmtracker.project_management_backend.notification.dto.NotificationResponse;
import com.pmtracker.project_management_backend.realtime.UserNotifiedEvent;
import com.pmtracker.project_management_backend.task.Task;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Уведомления пользователя (колокольчик в хедере). Событийные типы (task_assigned,
 * task_comment, task_mention) пишутся напрямую из TaskService/TaskCommentService, в той же транзакции,
 * что и само действие — тот же приём, что ActivityService.record (см. её комментарий).
 * Типы task_due_soon/task_overdue не событийные, а вычисляемые по расписанию — их
 * создаёт NotificationScheduler.
 * <p>
 * С 4.3 у уведомления есть второй канал доставки — почта. Решение «писать ли письмо и когда»
 * принимается здесь же, в той же транзакции: настройки получателя читаются один раз на
 * уведомление, и результат фиксируется в самой строке (email_sent_at), а не пересчитывается
 * потом. Иначе рассылке пришлось бы гадать, что человек хотел вчера, когда уведомление
 * создавалось, — а между созданием и вечерним дайджестом настройки вполне могли поменяться.
 * <p>
 * С 4.15 канала три: к колокольчику и почте добавился открытый поток в уже загруженную
 * вкладку (см. {@code realtime/}). Он не отдельная доставка, а способ показать содержимое
 * колокольчика без опроса раз в полминуты, поэтому и решения своего не принимает: событие
 * уходит на каждое созданное уведомление, а куда именно — знает {@code RealtimeBroadcaster}.
 * <p>
 * С 4.16 у получателя появилась вторая ось настройки — по проектам. Проверяется она ровно
 * в одном месте, в {@link #create}, и раньше всего остального: выключенный проект означает
 * тишину во всех трёх каналах сразу, а не «письма не шлём, а в колокольчик положим».
 */
@Service
public class NotificationService {

    private static final int PAGE_SIZE = 20;
    // Превью текста комментария в уведомлении — сам комментарий читается на странице задачи.
    private static final int COMMENT_EXCERPT_MAX_LENGTH = 140;

    /**
     * Новая задача в проекте — уведомление только для тех, кто выбрал по проекту режим
     * ALL (4.16). Ни постановщику, ни исполнителю оно не адресовано: первый её и создал,
     * второй узнаёт о ней из {@link #TYPE_TASK_ASSIGNED}.
     */
    public static final String TYPE_TASK_CREATED = "task_created";
    public static final String TYPE_TASK_ASSIGNED = "task_assigned";
    public static final String TYPE_TASK_COMMENT = "task_comment";
    public static final String TYPE_TASK_MENTION = "task_mention";
    public static final String TYPE_TASK_DUE_SOON = "task_due_soon";
    public static final String TYPE_TASK_OVERDUE = "task_overdue";

    private final NotificationRepository notificationRepository;
    private final NotificationSettingsRepository settingsRepository;
    private final ProjectNotificationSettingsService projectSettingsService;
    private final ProjectNotificationSettingsRepository projectSettingsRepository;
    private final UnsubscribeTokenService unsubscribeTokenService;
    private final ApplicationEventPublisher eventPublisher;

    public NotificationService(NotificationRepository notificationRepository,
                               NotificationSettingsRepository settingsRepository,
                               ProjectNotificationSettingsService projectSettingsService,
                               ProjectNotificationSettingsRepository projectSettingsRepository,
                               UnsubscribeTokenService unsubscribeTokenService,
                               ApplicationEventPublisher eventPublisher) {
        this.notificationRepository = notificationRepository;
        this.settingsRepository = settingsRepository;
        this.projectSettingsService = projectSettingsService;
        this.projectSettingsRepository = projectSettingsRepository;
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

    /**
     * Новый комментарий: упомянутые (4.5) плюс постановщик с исполнителем.
     *
     * @param mentioned участники проекта, упомянутые в тексте; список уже проверен на
     *                  членство в проекте (см. {@code TaskCommentService.resolveMentions})
     */
    @Transactional
    public void notifyTaskComment(Task task, User actor, String commentBody, Collection<User> mentioned) {
        Map<String, Object> payload = basePayload(task);
        payload.put("commentExcerpt", excerpt(commentBody));

        // Упоминание сильнее «прокомментировали вашу задачу» и потому раздаётся первым:
        // человеку, которого позвали по имени, должно прийти именно «вас упомянули», даже
        // если он же и постановщик. Двух уведомлений об одном комментарии не бывает —
        // notifiedUserIds общий на оба круга.
        Set<UUID> notifiedUserIds = new HashSet<>();
        notifyMentioned(task, actor, payload, mentioned, notifiedUserIds);

        // Постановщик и исполнитель уведомляются оба, но: не сам автор комментария,
        // и не дважды одному человеку, если он и постановщик, и исполнитель одновременно.
        //
        // Arrays.asList, а не List.of: исполнителя у задачи может не быть, а List.of падает
        // NPE на null-элементе — то есть любой комментарий к неназначенной задаче (а это
        // состояние по умолчанию для только что созданной) возвращал 500 вместо 201. Проверка
        // recipient == null ниже как раз и написана в расчёте на отсутствующего исполнителя.
        for (User recipient : Arrays.asList(task.getCreatedBy(), task.getAssignee())) {
            if (recipient == null || recipient.getId().equals(actor.getId()) || !notifiedUserIds.add(recipient.getId())) {
                continue;
            }
            create(recipient, actor, TYPE_TASK_COMMENT, task, payload);
        }

        // Наблюдатели проекта (4.16): режим ALL означает «все комментарии в проекте», а не
        // только в задачах, к которым я причастен. Тип тот же самый, task_comment, — событие
        // и правда то же самое, и второй тип ради него означал бы две формулировки одного
        // факта в колокольчике, в письме и в трёх локалях.
        notifyWatchers(task, actor, TYPE_TASK_COMMENT, payload, notifiedUserIds);
    }

    /**
     * Новая задача в проекте — тем, кто следит за проектом целиком (4.16).
     * <p>
     * Отдельного круга «постановщик и исполнитель» здесь нет и быть не может: постановщик
     * задачу только что создал сам, а исполнителю про неё в ту же секунду уходит
     * {@link #TYPE_TASK_ASSIGNED}. Поэтому единственные получатели — наблюдатели.
     */
    @Transactional
    public void notifyTaskCreated(Task task, User actor) {
        Set<UUID> notifiedUserIds = new HashSet<>();
        if (task.getAssignee() != null) {
            notifiedUserIds.add(task.getAssignee().getId());
        }
        notifyWatchers(task, actor, TYPE_TASK_CREATED, basePayload(task), notifiedUserIds);
    }

    /**
     * Разослать событие наблюдателям проекта — тем, кто выбрал по нему режим ALL (4.16).
     * <p>
     * {@code notifiedUserIds} общий с основным кругом получателей и потому обязателен:
     * наблюдатель — обычный участник проекта и вполне может быть заодно постановщиком или
     * упомянутым. Два уведомления об одном комментарии — ровно та цена, которую человек
     * заплатил бы за то, что попросил присылать больше.
     */
    private void notifyWatchers(Task task, User actor, String type,
                                Map<String, Object> payload, Set<UUID> notifiedUserIds) {
        for (User watcher : projectSettingsRepository.findWatchers(task.getProject().getId())) {
            if (watcher.getId().equals(actor.getId()) || !notifiedUserIds.add(watcher.getId())) {
                continue;
            }
            create(watcher, actor, type, task, payload);
        }
    }

    /**
     * Правка комментария (4.4): уведомляются только те, кого добавили в текст этой правкой.
     * Ни постановщик, ни исполнитель повторно не уведомляются — комментарий тот же самый,
     * и «прокомментировал вашу задачу» они уже получили, когда он появился.
     */
    @Transactional
    public void notifyCommentMentions(Task task, User actor, String commentBody, Collection<User> mentioned) {
        if (mentioned.isEmpty()) {
            return;
        }
        Map<String, Object> payload = basePayload(task);
        payload.put("commentExcerpt", excerpt(commentBody));
        notifyMentioned(task, actor, payload, mentioned, new HashSet<>());
    }

    /**
     * Упомянуть себя — не событие: человек, написавший «@я», и так знает, что он это
     * написал. Проверка та же, что у самоназначения, и по той же причине.
     */
    private void notifyMentioned(Task task, User actor, Map<String, Object> payload,
                                 Collection<User> mentioned, Set<UUID> notifiedUserIds) {
        for (User recipient : mentioned) {
            if (recipient.getId().equals(actor.getId()) || !notifiedUserIds.add(recipient.getId())) {
                continue;
            }
            create(recipient, actor, TYPE_TASK_MENTION, task, payload);
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
        // Ответ — «создали ли», а не «подходит ли задача»: сканер по нему считает свою
        // работу, и выключенный проект (4.16) для него ничем не отличается от уже
        // разосланного алерта — делать было нечего.
        return create(recipient, null, type, task, payload);
    }

    /**
     * @return true, если уведомление действительно создано; false — если получатель
     *         выключил себе этот проект (4.16)
     */
    private boolean create(User recipient, User actor, String type, Task task, Map<String, Object> payload) {
        // Единственное место, где проверяется режим проекта, — и этого достаточно, потому
        // что через create проходят все пять типов, включая те, что создаёт планировщик.
        // Проверка стоит до сохранения, а не до отправки письма: «отписаться от проекта»
        // означает «не показывать это и в колокольчике» — иначе выключение отличалось бы
        // от глобальной настройки почты (4.3) только формулировкой.
        if (projectSettingsService.modeOf(task.getProject().getId(), recipient.getId())
                == ProjectNotificationMode.MUTED) {
            return false;
        }
        Notification notification = new Notification();
        notification.setRecipient(recipient);
        notification.setActor(actor);
        notification.setType(type);
        notification.setTask(task);
        notification.setPayload(payload);
        notificationRepository.save(notification);
        planEmail(notification, recipient, actor);
        // Третий канал доставки того же уведомления (4.15): колокольчик в уже открытой
        // вкладке. Не альтернатива почте, а замена опросу раз в 30 секунд — тому самому,
        // из-за которого «вас упомянули» приезжало в среднем через четверть минуты после
        // того, как это написали.
        eventPublisher.publishEvent(new UserNotifiedEvent(
                recipient.getId(),
                type,
                task.getProject().getId(),
                task.getId(),
                actor != null ? actor.getId() : null));
        return true;
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

    /** То же для набора задач сразу (массовая правка, 4.6). */
    @Transactional
    public void clearDueDateAlerts(Collection<UUID> taskIds) {
        if (taskIds.isEmpty()) {
            return;
        }
        notificationRepository.deleteDueDateAlertsForTasks(taskIds);
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
