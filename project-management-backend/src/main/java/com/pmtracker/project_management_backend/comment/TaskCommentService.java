package com.pmtracker.project_management_backend.comment;

import com.pmtracker.project_management_backend.activity.ActivityService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.comment.dto.CommentResponse;
import com.pmtracker.project_management_backend.comment.dto.CreateCommentRequest;
import com.pmtracker.project_management_backend.comment.dto.UpdateCommentRequest;
import com.pmtracker.project_management_backend.common.exception.CommentNotFoundException;
import com.pmtracker.project_management_backend.common.exception.NotCommentAuthorException;
import com.pmtracker.project_management_backend.common.exception.NotCommentOwnerException;
import com.pmtracker.project_management_backend.common.exception.TaskNotFoundException;
import com.pmtracker.project_management_backend.notification.NotificationService;
import com.pmtracker.project_management_backend.project.ProjectAccessService;
import com.pmtracker.project_management_backend.project.ProjectMemberRepository;
import com.pmtracker.project_management_backend.project.ProjectRole;
import com.pmtracker.project_management_backend.task.Task;
import com.pmtracker.project_management_backend.task.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class TaskCommentService {

    public static final String SORT_OLDEST = "oldest";

    /**
     * Потолок числа упомянутых в одном комментарии (4.5). В 2000 символов тела помещается
     * заметно больше сотни никнеймов, а каждый упомянутый — это уведомление и, по умолчанию,
     * письмо. Двадцати хватает, чтобы позвать хоть всю команду небольшого проекта, и мало,
     * чтобы одним сообщением устроить рассылку по всем участникам большого. Отрезается хвост
     * (никнеймы идут в порядке появления в тексте) — то есть работает начало списка, которое
     * человек и писал осмысленно.
     */
    private static final int MAX_MENTIONS_PER_COMMENT = 20;

    private final TaskCommentRepository taskCommentRepository;
    private final TaskRepository taskRepository;
    private final ProjectAccessService projectAccessService;
    private final ProjectMemberRepository projectMemberRepository;
    private final ActivityService activityService;
    private final NotificationService notificationService;

    public TaskCommentService(TaskCommentRepository taskCommentRepository,
                               TaskRepository taskRepository,
                               ProjectAccessService projectAccessService,
                               ProjectMemberRepository projectMemberRepository,
                               ActivityService activityService,
                               NotificationService notificationService) {
        this.taskCommentRepository = taskCommentRepository;
        this.taskRepository = taskRepository;
        this.projectAccessService = projectAccessService;
        this.projectMemberRepository = projectMemberRepository;
        this.activityService = activityService;
        this.notificationService = notificationService;
    }

    @Transactional
    public CommentResponse create(User currentUser, UUID taskId, CreateCommentRequest request) {
        Task task = findTaskOrThrow(taskId);
        ProjectRole role = projectAccessService.requireMembership(task.getProject().getId(), currentUser);
        projectAccessService.requireWriteRole(task.getProject(), role, ProjectRole.MEMBER);

        TaskComment comment = new TaskComment();
        comment.setTask(task);
        comment.setAuthor(currentUser);
        comment.setBody(request.body());
        taskCommentRepository.save(comment);

        activityService.record(task.getProject(), currentUser, "comment_added", task,
                Map.of("taskNumber", task.getTaskNumber(), "title", task.getTitle()));
        notificationService.notifyTaskComment(task, currentUser, comment.getBody(),
                resolveMentions(task, comment.getBody(), Set.of()));

        return CommentResponse.from(comment);
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> list(User currentUser, UUID taskId, String sort) {
        Task task = findTaskOrThrow(taskId);
        projectAccessService.requireMembership(task.getProject().getId(), currentUser);

        // Любое значение, кроме "oldest", трактуется как дефолт "newest" — незачем
        // отвечать 400 на опечатку в необязательном параметре сортировки.
        var comments = SORT_OLDEST.equals(sort)
                ? taskCommentRepository.findByTaskIdOrderByCreatedAtAsc(taskId)
                : taskCommentRepository.findByTaskIdOrderByCreatedAtDesc(taskId);
        return comments.stream().map(CommentResponse::from).toList();
    }

    /**
     * Правка комментария (4.4). Только автор — в отличие от удаления, где OWNER/ADMIN
     * выступают модераторами. Удаление чужого комментария видно: сообщение исчезает целиком,
     * и спорить об этом можно с тем, кто это сделал. Правка чужого не видна никак: подпись
     * остаётся прежней, а слова становятся другими, и человек оказывается автором того, чего
     * не писал. Модераторская нужда «убрать лишнее из чужого текста» закрывается удалением.
     * <p>
     * Роль MEMBER проверяется дополнительно к авторству — на случай, если автора после
     * написания комментария понизили до VIEWER: наблюдатель в проекте не пишет, в том числе
     * и задним числом.
     * <p>
     * Окна «правка возможна только N минут» нет намеренно: оно защищает от подмены смысла
     * в уже прочитанном треде, но здесь его цену платил бы прежде всего тот, ради кого
     * пункт и делался, — человек, заметивший опечатку не сразу. Отметка «изменено» с точным
     * временем правки честнее ограничения: она видна всем и не мешает исправить текст.
     * <p>
     * Версии (3.4) у комментария нет и не заводится: править его может ровно один человек,
     * и конфликт возможен только между двумя его же вкладками — там побеждает последнее
     * сохранение, что и ожидается. Одинаковый текст не считается правкой вовсе: «сохранил,
     * ничего не поменяв» не должно вешать на комментарий пометку «изменено».
     */
    @Transactional
    public CommentResponse update(User currentUser, UUID commentId, UpdateCommentRequest request) {
        TaskComment comment = taskCommentRepository.findById(commentId).orElseThrow(CommentNotFoundException::new);
        Task task = comment.getTask();
        ProjectRole role = projectAccessService.requireMembership(task.getProject().getId(), currentUser);
        projectAccessService.requireWriteRole(task.getProject(), role, ProjectRole.MEMBER);
        if (!comment.getAuthor().getId().equals(currentUser.getId())) {
            throw new NotCommentAuthorException();
        }

        String previousBody = comment.getBody();
        if (previousBody.equals(request.body())) {
            return CommentResponse.from(comment);
        }
        comment.setBody(request.body());
        comment.setEditedAt(Instant.now());

        // Уведомляются только те, кого в этом тексте раньше не было. Иначе исправленная
        // опечатка звала бы в тред заново всех, кто уже пришёл, — а поправить опечатку в
        // комментарии с пятью упоминаниями дело обычное. Заново «прокомментировал вашу
        // задачу» постановщику и исполнителю не уходит по той же причине: комментарий тот
        // же самый, они о нём уже знают.
        notificationService.notifyCommentMentions(task, currentUser, comment.getBody(),
                resolveMentions(task, comment.getBody(), MentionParser.parse(previousBody)));

        return CommentResponse.from(comment);
    }

    @Transactional
    public void delete(User currentUser, UUID commentId) {
        TaskComment comment = taskCommentRepository.findById(commentId).orElseThrow(CommentNotFoundException::new);
        UUID projectId = comment.getTask().getProject().getId();
        ProjectRole role = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireWriteRole(comment.getTask().getProject(), role, ProjectRole.MEMBER);

        boolean isAuthor = comment.getAuthor().getId().equals(currentUser.getId());
        boolean isModerator = role.isAtLeast(ProjectRole.ADMIN);
        if (!isAuthor && !isModerator) {
            throw new NotCommentOwnerException();
        }

        taskCommentRepository.delete(comment);
    }

    /**
     * Кого из упомянутых в тексте надо уведомить: никнеймы из тела, минус уже упомянутые
     * раньше ({@code alreadyMentioned} — упоминания предыдущей редакции, при создании пусто),
     * сведённые к реальным участникам этого проекта.
     * <p>
     * Фильтр по участникам делает запрос в БД ({@code findUsersByProjectIdAndUsernameIn}) и он
     * же служит проверкой прав: никнейм постороннего просто не найдётся, и упоминание тихо
     * останется текстом. Тихо — намеренно: отвечать 400 на «в проекте нет такого никнейма»
     * значило бы, что опечатка в упоминании отменяет отправку всего комментария, а заодно
     * превращало бы форму комментария в способ перебирать чужие никнеймы по проектам.
     */
    private List<User> resolveMentions(Task task, String body, Set<String> alreadyMentioned) {
        Set<String> usernames = new LinkedHashSet<>(MentionParser.parse(body));
        usernames.removeAll(alreadyMentioned);
        if (usernames.isEmpty()) {
            return List.of();
        }
        List<String> capped = usernames.stream().limit(MAX_MENTIONS_PER_COMMENT).toList();
        return projectMemberRepository.findUsersByProjectIdAndUsernameIn(task.getProject().getId(), capped);
    }

    private Task findTaskOrThrow(UUID taskId) {
        return taskRepository.findById(taskId).orElseThrow(TaskNotFoundException::new);
    }
}
