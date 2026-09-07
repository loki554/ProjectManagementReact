package com.pmtracker.project_management_backend.task;

import com.pmtracker.project_management_backend.activity.ActivityService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.category.Category;
import com.pmtracker.project_management_backend.category.CategoryService;
import com.pmtracker.project_management_backend.common.dto.PageResponse;
import com.pmtracker.project_management_backend.common.exception.AssigneeNotProjectMemberException;
import com.pmtracker.project_management_backend.common.exception.ConcurrentModificationConflictException;
import com.pmtracker.project_management_backend.common.exception.InvalidTargetPositionException;
import com.pmtracker.project_management_backend.common.exception.NoBulkChangesRequestedException;
import com.pmtracker.project_management_backend.common.exception.ParentTaskDeletedException;
import com.pmtracker.project_management_backend.common.exception.ParentTaskNotFoundException;
import com.pmtracker.project_management_backend.common.exception.ParentTaskProjectMismatchException;
import com.pmtracker.project_management_backend.common.exception.TagNotFoundException;
import com.pmtracker.project_management_backend.common.exception.TagProjectMismatchException;
import com.pmtracker.project_management_backend.common.exception.TaskNotFoundException;
import com.pmtracker.project_management_backend.common.exception.TaskHasOpenBlockersException;
import com.pmtracker.project_management_backend.common.exception.TaskStatusConflictException;
import com.pmtracker.project_management_backend.notification.NotificationService;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectAccessService;
import com.pmtracker.project_management_backend.project.ProjectMember;
import com.pmtracker.project_management_backend.project.ProjectMemberRepository;
import com.pmtracker.project_management_backend.project.ProjectRepository;
import com.pmtracker.project_management_backend.project.ProjectRole;
import com.pmtracker.project_management_backend.tag.Tag;
import com.pmtracker.project_management_backend.tag.TagRepository;
import com.pmtracker.project_management_backend.task.dto.BulkUpdateTasksRequest;
import com.pmtracker.project_management_backend.task.dto.BulkUpdateTasksResponse;
import com.pmtracker.project_management_backend.task.dto.CreateTaskRequest;
import com.pmtracker.project_management_backend.task.dto.MyActiveTaskResponse;
import com.pmtracker.project_management_backend.task.dto.TaskResponse;
import com.pmtracker.project_management_backend.task.dto.TrashedTaskResponse;
import com.pmtracker.project_management_backend.task.dto.UpdateTaskRequest;
import com.pmtracker.project_management_backend.task.dto.UpdateTaskStatusRequest;
import com.pmtracker.project_management_backend.timelog.TimeLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TaskService {

    // Размер страницы табличного списка задач (3.3). Не фиксирован, как у /tasks/mine: там
    // страница — часть вёрстки виджета, здесь её выбирает пользователь. Потолок нужен, чтобы
    // ?size=1000000 не возвращал ровно то, от чего пагинацию и вводили.
    private static final int DEFAULT_TASK_PAGE_SIZE = 50;
    private static final int MAX_TASK_PAGE_SIZE = 200;

    /**
     * Сколько задача лежит в корзине, прежде чем её физически удалит TaskCleanupJob (3.5).
     * Значение живёт здесь, а не в задании чистки: показать срок в интерфейсе и соблюсти
     * его при удалении должно одно и то же число.
     */
    static final Duration TRASH_RETENTION = Duration.ofDays(30);

    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final ProjectAccessService projectAccessService;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectRepository projectRepository;
    private final TagRepository tagRepository;
    private final CategoryService categoryService;
    private final TimeLogRepository timeLogRepository;
    private final ActivityService activityService;
    private final NotificationService notificationService;

    public TaskService(TaskRepository taskRepository,
                        TaskDependencyRepository taskDependencyRepository,
                        ProjectAccessService projectAccessService,
                        ProjectMemberRepository projectMemberRepository,
                        ProjectRepository projectRepository,
                        TagRepository tagRepository,
                        CategoryService categoryService,
                        TimeLogRepository timeLogRepository,
                        ActivityService activityService,
                        NotificationService notificationService) {
        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
        this.projectAccessService = projectAccessService;
        this.projectMemberRepository = projectMemberRepository;
        this.projectRepository = projectRepository;
        this.tagRepository = tagRepository;
        this.categoryService = categoryService;
        this.timeLogRepository = timeLogRepository;
        this.activityService = activityService;
        this.notificationService = notificationService;
    }

    @Transactional
    public TaskResponse create(User currentUser, UUID projectId, CreateTaskRequest request) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        ProjectRole role = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(role, ProjectRole.MEMBER);

        Task task = new Task();
        task.setProject(project);
        task.setParentTask(null);
        task.setTaskNumber(projectRepository.reserveNextTaskNumber(projectId));
        applyCommonFields(task, task.getProject(), currentUser, request.title(), request.description(),
                request.assigneeId(), request.dueDate(), request.tagId(), request.category());
        TaskUrgency urgency = request.urgency() != null ? request.urgency() : TaskUrgency.MEDIUM;
        task.setUrgency(urgency);
        TaskStatus status = request.status() != null ? request.status() : TaskStatus.NEW;
        task.setStatus(status);
        task.setCreatedBy(currentUser);
        task.setPosition(nextPosition(projectId, status));
        taskRepository.save(task);
        activityService.record(project, currentUser, "task_created", task,
                Map.of("taskNumber", task.getTaskNumber(), "title", task.getTitle()));
        notificationService.notifyTaskAssigned(task, currentUser, task.getAssignee());
        // 0 блокеров без запроса: связи (4.8) заводятся отдельной ручкой уже после
        // создания, у только что созданной задачи их быть неоткуда.
        return TaskResponse.from(task, timeLogRepository.sumHoursByTaskId(task.getId()), 0);
    }

    /**
     * Табличный список задач проекта: страница + серверные фильтры и сортировка (3.3).
     * Раньше метод отдавал все top-level задачи проекта одним массивом, а фильтровал и
     * сортировал их фронтенд — на проекте в пару тысяч задач это мегабайты JSON на каждое
     * открытие вкладки и подвисающий рендер.
     */
    @Transactional(readOnly = true)
    public PageResponse<TaskResponse> list(User currentUser, UUID projectId, TaskListQuery query, int page, int size) {
        projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);

        if (query.parentId() != null) {
            Task parent = taskRepository.findById(query.parentId()).orElseThrow(ParentTaskNotFoundException::new);
            if (!parent.getProject().getId().equals(projectId)) {
                throw new ParentTaskProjectMismatchException();
            }
        }

        Pageable pageable = PageRequest.of(Math.max(page, 0), clampPageSize(size));
        // «Мои задачи» превращаются в фильтр по конкретному исполнителю здесь и только здесь:
        // текущий пользователь известен на этом уровне, а репозиторий получает обычный
        // assigneeId (см. TaskListQuery.resolveViewer).
        Page<Task> result = taskRepository.search(projectId, query.resolveViewer(currentUser.getId()), pageable);
        return PageResponse.from(new PageImpl<>(toResponses(result.getContent()), pageable, result.getTotalElements()));
    }

    /**
     * Задачи для канбан-доски — все top-level, без пагинации; см. TaskRepository.findBoardTasks
     * о том, почему именно здесь она не нужна.
     */
    @Transactional(readOnly = true)
    public List<TaskResponse> listBoard(User currentUser, UUID projectId) {
        projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);
        return toResponses(taskRepository.findBoardTasks(projectId));
    }

    /**
     * Проверка версии до применения правок (3.4). Именно до: иначе в ленту активности
     * успели бы уехать события об изменениях, которые в итоге откатятся.
     */
    private static void requireCurrentVersion(Long expected, long actual) {
        if (expected == null || expected != actual) {
            throw new ConcurrentModificationConflictException();
        }
    }

    private static int clampPageSize(int size) {
        if (size < 1) {
            return DEFAULT_TASK_PAGE_SIZE;
        }
        return Math.min(size, MAX_TASK_PAGE_SIZE);
    }

    private List<TaskResponse> toResponses(List<Task> tasks) {
        List<UUID> taskIds = tasks.stream().map(Task::getId).toList();
        Map<UUID, BigDecimal> hoursByTask = loadHoursTotals(taskIds);
        Map<UUID, Integer> blockersByTask = loadOpenBlockerCounts(taskIds);
        return tasks.stream()
                .map(t -> TaskResponse.from(t,
                        hoursByTask.getOrDefault(t.getId(), BigDecimal.ZERO),
                        blockersByTask.getOrDefault(t.getId(), 0)))
                .toList();
    }

    @Transactional(readOnly = true)
    public TaskResponse getById(User currentUser, UUID taskId) {
        Task task = findTaskOrThrow(taskId);
        projectAccessService.requireMembership(task.getProject().getId(), currentUser);
        return TaskResponse.from(task, timeLogRepository.sumHoursByTaskId(taskId), openBlockerCount(taskId));
    }

    // Для читаемых URL (/projects/{slug}/tasks/{taskNumber}, см. taskNumber в Task.java) —
    // номер уникален только в пределах проекта, поэтому в отличие от getById проекту нужно
    // передавать явно.
    @Transactional(readOnly = true)
    public TaskResponse getByProjectAndNumber(User currentUser, UUID projectId, int taskNumber) {
        projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);
        Task task = taskRepository.findByProjectIdAndTaskNumber(projectId, taskNumber)
                .orElseThrow(TaskNotFoundException::new);
        return TaskResponse.from(task, timeLogRepository.sumHoursByTaskId(task.getId()), openBlockerCount(task.getId()));
    }

    @Transactional
    public TaskResponse update(User currentUser, UUID taskId, UpdateTaskRequest request) {
        Task task = findTaskOrThrow(taskId);
        UUID projectId = task.getProject().getId();
        ProjectRole role = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(role, ProjectRole.MEMBER);
        requireCurrentVersion(request.version(), task.getVersion());
        // До первой правки: отказ обязан не оставить после себя ни изменённых полей, ни
        // событий в ленте — ровно как проверка версии строкой выше.
        requireBlockersClosed(task, request.status(), request.ignoreBlockers());

        // Снапшот "до" — после applyCommonFields по одному событию на каждое реально
        // изменившееся поле (описание сознательно не в ленте: диффы длинного текста шумят).
        String oldTitle = task.getTitle();
        TaskStatus oldStatus = task.getStatus();
        String oldAssignee = displayName(task.getAssignee());
        TaskUrgency oldUrgency = task.getUrgency();
        Instant oldDueDate = task.getDueDate();
        String oldTag = task.getTag() != null ? task.getTag().getName() : null;
        String oldCategory = categoryName(task);

        applyCommonFields(task, task.getProject(), currentUser, request.title(), request.description(),
                request.assigneeId(), request.dueDate(), request.tagId(), request.category());
        task.setStatus(request.status());
        task.setUrgency(request.urgency());
        // saveAndFlush — см. WikiService.update: ответ должен нести уже увеличенную версию.
        taskRepository.saveAndFlush(task);

        if (!Objects.equals(oldTitle, task.getTitle())) {
            recordFieldChange(task, currentUser, "task_title_changed", oldTitle, task.getTitle());
        }
        if (oldStatus != task.getStatus()) {
            recordFieldChange(task, currentUser, "task_status_changed", oldStatus.name(), task.getStatus().name());
        }
        boolean assigneeChanged = !Objects.equals(oldAssignee, displayName(task.getAssignee()));
        if (assigneeChanged) {
            recordFieldChange(task, currentUser, "task_assignee_changed", oldAssignee, displayName(task.getAssignee()));
            notificationService.notifyTaskAssigned(task, currentUser, task.getAssignee());
        }
        if (oldUrgency != task.getUrgency()) {
            recordFieldChange(task, currentUser, "task_urgency_changed", oldUrgency.name(), task.getUrgency().name());
        }
        boolean dueDateChanged = !Objects.equals(oldDueDate, task.getDueDate());
        if (dueDateChanged) {
            recordFieldChange(task, currentUser, "task_due_date_changed",
                    oldDueDate != null ? oldDueDate.toString() : null,
                    task.getDueDate() != null ? task.getDueDate().toString() : null);
        }
        String newTag = task.getTag() != null ? task.getTag().getName() : null;
        if (!Objects.equals(oldTag, newTag)) {
            recordFieldChange(task, currentUser, "task_tag_changed", oldTag, newTag);
        }
        String newCategory = categoryName(task);
        if (!Objects.equals(oldCategory, newCategory)) {
            recordFieldChange(task, currentUser, "task_category_changed", oldCategory, newCategory);
        }

        // Дедлайн сдвинулся, исполнитель сменился или задача больше не активна — прежние
        // task_due_soon/task_overdue (если были) больше не отражают реальность; следующий
        // тик NotificationScheduler создаст их заново, если условия всё ещё выполняются.
        boolean statusBecameInactive = oldStatus != task.getStatus() && INACTIVE_STATUSES.contains(task.getStatus());
        if (dueDateChanged || assigneeChanged || statusBecameInactive) {
            notificationService.clearDueDateAlerts(task.getId());
        }

        return TaskResponse.from(task, timeLogRepository.sumHoursByTaskId(taskId), openBlockerCount(taskId));
    }

    // Instant в payload сериализуем строками заранее (см. task_due_date_changed), а null'ы
    // допустимы ("поле снято") — поэтому LinkedHashMap, а не Map.of.
    private void recordFieldChange(Task task, User actor, String type, Object oldValue, Object newValue) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskNumber", task.getTaskNumber());
        payload.put("title", task.getTitle());
        payload.put("old", oldValue);
        payload.put("new", newValue);
        activityService.record(task.getProject(), actor, type, task, payload);
    }

    private static String categoryName(Task task) {
        Category category = task.getCategory();
        return category != null ? category.getName() : null;
    }

    private static String displayName(User user) {
        return user != null ? user.getLastName() + " " + user.getFirstName() : null;
    }

    @Transactional
    public TaskResponse updateStatus(User currentUser, UUID taskId, UpdateTaskStatusRequest request) {
        Task task = findTaskOrThrow(taskId);
        UUID projectId = task.getProject().getId();
        ProjectRole role = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(role, ProjectRole.MEMBER);

        TaskStatus oldStatus = task.getStatus();
        if (oldStatus != request.expectedStatus()) {
            throw new TaskStatusConflictException();
        }
        requireBlockersClosed(task, request.status(), request.ignoreBlockers());

        UUID parentId = task.getParentTask() != null ? task.getParentTask().getId() : null;
        TaskStatus newStatus = request.status();
        int targetIndex = request.position();

        if (oldStatus == newStatus) {
            List<Task> column = new ArrayList<>(taskRepository.findSiblingsByStatus(projectId, oldStatus, parentId));
            column.removeIf(t -> t.getId().equals(taskId));
            if (targetIndex > column.size()) {
                throw new InvalidTargetPositionException();
            }
            column.add(targetIndex, task);
            renumber(column);
        } else {
            List<Task> oldColumn = new ArrayList<>(taskRepository.findSiblingsByStatus(projectId, oldStatus, parentId));
            oldColumn.removeIf(t -> t.getId().equals(taskId));
            renumber(oldColumn);

            List<Task> newColumn = new ArrayList<>(taskRepository.findSiblingsByStatus(projectId, newStatus, parentId));
            if (targetIndex > newColumn.size()) {
                throw new InvalidTargetPositionException();
            }
            task.setStatus(newStatus);
            newColumn.add(targetIndex, task);
            renumber(newColumn);
            // Перестановка внутри колонки (oldStatus == newStatus) — не событие для ленты,
            // фиксируем только реальную смену статуса.
            recordFieldChange(task, currentUser, "task_status_changed", oldStatus.name(), newStatus.name());
            if (INACTIVE_STATUSES.contains(newStatus)) {
                notificationService.clearDueDateAlerts(task.getId());
            }
        }

        return TaskResponse.from(task, timeLogRepository.sumHoursByTaskId(taskId), openBlockerCount(taskId));
    }

    private void renumber(List<Task> orderedColumn) {
        for (int i = 0; i < orderedColumn.size(); i++) {
            orderedColumn.get(i).setPosition(i);
        }
    }

    // ------------------------------------------------------------ массовые операции (4.6)

    /**
     * Массовая правка выделенных задач: один статус/исполнитель/тэг/срок на весь набор.
     * Двадцать кликов по форме задачи превращаются в одно действие — и, что важнее, в одну
     * транзакцию: либо новый статус получили все двадцать задач, либо ни одна.
     *
     * <p><b>Неизвестный id отменяет весь запрос</b> (404 TASK_NOT_FOUND), а не пропускается
     * молча. Пропуск выглядит дружелюбнее ровно до первого случая, когда человек видит
     * «обновлено 17», думает, что это про его двадцать, и не узнаёт, какие три не поехали
     * и почему. Отказ целиком — единственный исход, который читается однозначно: список
     * устарел, перечитайте и повторите. Сюда же попадает и задача, уехавшая в корзину, пока
     * список висел открытым (см. findAllByProjectIdAndIdIn), и чужая задача из другого
     * проекта — разницы между ними ответ не делает.
     *
     * <p><b>Уведомления и лента — по одному событию на задачу</b>, теми же типами, что и
     * одиночная правка. Отдельного «изменено массово» в ленте нет намеренно: лента задачи
     * отвечает на вопрос «что с ней происходило», и событие, спрятанное в сводку по проекту,
     * из карточки задачи просто исчезло бы. Цена — двадцать писем тому, на кого разом
     * назначили двадцать задач; но столько же их пришло бы и от двадцати одиночных правок,
     * а способ получать реже у адресата уже есть — режим дайджеста в настройках (4.3).
     * Потолок выделения (200, см. BulkUpdateTasksRequest) заодно ограничивает и это число.
     *
     * <p>Порядок внутри метода не случаен: все запросы — резолв исполнителя с тэгом и
     * загрузка канбан-колонок — сделаны до первой правки. JPQL-запрос сбрасывает в БД
     * накопленные изменения перед выполнением, и колонка, прочитанная после смены статуса
     * хотя бы одной задачи, вернула бы уже переехавшую задачу в новом составе.
     */
    @Transactional
    public BulkUpdateTasksResponse bulkUpdate(User currentUser, UUID projectId, BulkUpdateTasksRequest request) {
        projectAccessService.findProjectOrThrow(projectId);
        ProjectRole role = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(role, ProjectRole.MEMBER);

        if (!request.hasChanges()) {
            throw new NoBulkChangesRequestedException();
        }

        // distinct до сравнения размеров: дубль в выделении — не повод отвечать 404,
        // сервер и так применит правку к задаче один раз.
        List<UUID> requestedIds = request.taskIds().stream().distinct().toList();
        List<Task> tasks = taskRepository.findAllByProjectIdAndIdIn(projectId, requestedIds);
        if (tasks.size() != requestedIds.size()) {
            throw new TaskNotFoundException();
        }
        requireBlockersClosedForAll(tasks, request.status(), request.ignoreBlockers());

        User newAssignee = request.clearAssignee() ? null : resolveAssignee(projectId, request.assigneeId());
        Tag newTag = request.clearTag() ? null : resolveTag(projectId, request.tagId());
        Instant newDueDate = request.clearDueDate() ? null : request.dueDate();
        Map<ColumnKey, List<Task>> columns = loadAffectedColumns(projectId, tasks, request.status());

        // Задачи, у которых прежние "скоро истекает"/"просрочена" перестали отражать
        // реальность — по тем же трём поводам, что и в update(): сдвинулся срок, сменился
        // исполнитель, задача закрылась. Собираем в набор и чистим одним DELETE в конце.
        Set<UUID> staleAlertTaskIds = new HashSet<>();
        List<Task> movedToNewStatus = new ArrayList<>();
        int updated = 0;

        for (Task task : tasks) {
            boolean changed = false;

            if (request.status() != null && task.getStatus() != request.status()) {
                TaskStatus oldStatus = task.getStatus();
                task.setStatus(request.status());
                movedToNewStatus.add(task);
                recordFieldChange(task, currentUser, "task_status_changed", oldStatus.name(), request.status().name());
                if (INACTIVE_STATUSES.contains(request.status())) {
                    staleAlertTaskIds.add(task.getId());
                }
                changed = true;
            }

            if (request.assigneeRequested() && !sameEntity(task.getAssignee(), newAssignee)) {
                String oldAssignee = displayName(task.getAssignee());
                task.setAssignee(newAssignee);
                recordFieldChange(task, currentUser, "task_assignee_changed", oldAssignee, displayName(newAssignee));
                notificationService.notifyTaskAssigned(task, currentUser, newAssignee);
                staleAlertTaskIds.add(task.getId());
                changed = true;
            }

            if (request.tagRequested() && !sameEntity(task.getTag(), newTag)) {
                String oldTag = task.getTag() != null ? task.getTag().getName() : null;
                task.setTag(newTag);
                recordFieldChange(task, currentUser, "task_tag_changed", oldTag,
                        newTag != null ? newTag.getName() : null);
                changed = true;
            }

            if (request.dueDateRequested() && !Objects.equals(task.getDueDate(), newDueDate)) {
                Instant oldDueDate = task.getDueDate();
                task.setDueDate(newDueDate);
                recordFieldChange(task, currentUser, "task_due_date_changed",
                        oldDueDate != null ? oldDueDate.toString() : null,
                        newDueDate != null ? newDueDate.toString() : null);
                staleAlertTaskIds.add(task.getId());
                changed = true;
            }

            if (changed) {
                updated++;
            }
        }

        restackColumns(columns, movedToNewStatus, request.status());
        notificationService.clearDueDateAlerts(staleAlertTaskIds);

        return new BulkUpdateTasksResponse(updated);
    }

    /**
     * Канбан-колонка — тот же скоуп, что у перетаскивания карточки: (родитель, статус) в
     * пределах проекта, см. TaskRepository.findSiblingsByStatus. Родитель может быть null
     * (top-level задача), поэтому именно record с nullable-полем, а не строковый ключ.
     */
    private record ColumnKey(UUID parentId, TaskStatus status) {
    }

    /**
     * Заранее вычитывает все колонки, которых коснётся смена статуса: покидаемые (по одной
     * на каждый встреченный старый статус в пределах родителя) и целевую. Задача, у которой
     * запрошенный статус уже стоит, не переезжает и колонок не задевает.
     */
    private Map<ColumnKey, List<Task>> loadAffectedColumns(UUID projectId, List<Task> tasks, TaskStatus newStatus) {
        if (newStatus == null) {
            return Map.of();
        }
        Map<ColumnKey, List<Task>> columns = new LinkedHashMap<>();
        for (Task task : tasks) {
            if (task.getStatus() == newStatus) {
                continue;
            }
            UUID parentId = task.getParentTask() != null ? task.getParentTask().getId() : null;
            for (TaskStatus status : List.of(task.getStatus(), newStatus)) {
                columns.computeIfAbsent(new ColumnKey(parentId, status), key ->
                        new ArrayList<>(taskRepository.findSiblingsByStatus(projectId, key.status(), key.parentId())));
            }
        }
        return columns;
    }

    /**
     * Пересчёт position после массовой смены статуса: переехавшие задачи вынимаются из
     * покидаемых колонок и дописываются в хвост целевой, после чего каждая затронутая
     * колонка перенумеровывается 0..n-1 — ровно как после одиночного перетаскивания.
     *
     * <p>В хвост, а не в начало: массовая правка — это «убрать разобранное с глаз», и
     * вклиниваться в начало чужой колонки, где сверху лежит то, чем занимаются сейчас, она
     * не должна. Порядок переехавших между собой — по номеру задачи (в нём их отдал
     * findAllByProjectIdAndIdIn): порядок выделения на клиенте зависит от того, в каком
     * направлении человек ставил галочки, и переносить его в общую доску незачем.
     */
    private void restackColumns(Map<ColumnKey, List<Task>> columns, List<Task> moved, TaskStatus newStatus) {
        if (moved.isEmpty()) {
            return;
        }
        Set<UUID> movedIds = moved.stream().map(Task::getId).collect(Collectors.toSet());
        columns.values().forEach(column -> column.removeIf(t -> movedIds.contains(t.getId())));
        for (Task task : moved) {
            UUID parentId = task.getParentTask() != null ? task.getParentTask().getId() : null;
            columns.get(new ColumnKey(parentId, newStatus)).add(task);
        }
        columns.values().forEach(this::renumber);
    }

    /** Сравнение "то же самое или другое" для необязательных ссылок задачи (исполнитель, тэг). */
    private static boolean sameEntity(User left, User right) {
        return Objects.equals(left != null ? left.getId() : null, right != null ? right.getId() : null);
    }

    private static boolean sameEntity(Tag left, Tag right) {
        return Objects.equals(left != null ? left.getId() : null, right != null ? right.getId() : null);
    }

    /**
     * Удаление задачи (3.5) — теперь мягкое: задача уезжает в корзину проекта на 30 дней
     * вместе с подзадачами, комментариями, вложениями и залогированным временем, которые
     * раньше исчезали безвозвратно по ON DELETE CASCADE.
     */
    @Transactional
    public void delete(User currentUser, UUID taskId) {
        Task task = findTaskOrThrow(taskId);
        ProjectRole role = projectAccessService.requireMembership(task.getProject().getId(), currentUser);
        projectAccessService.requireRole(role, ProjectRole.MEMBER);
        // task = null в событии: задача перестаёт быть видимой для JPA сразу после UPDATE,
        // и ссылка на неё из ленты активности вела бы в никуда — вернее, вела бы в 404 до
        // самого восстановления. Идентичность задачи — в снапшоте payload.
        activityService.record(task.getProject(), currentUser, "task_deleted", null,
                Map.of("taskNumber", task.getTaskNumber(), "title", task.getTitle()));
        taskRepository.softDelete(taskId, Instant.now());
    }

    /** Содержимое корзины проекта (3.5). Смотреть могут все участники, как и сами задачи. */
    @Transactional(readOnly = true)
    public List<TrashedTaskResponse> listTrash(User currentUser, UUID projectId) {
        projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);
        return taskRepository.findTrashed(projectId).stream()
                .map(t -> TrashedTaskResponse.from(t, t.getDeletedAt().plus(TRASH_RETENTION)))
                .toList();
    }

    /**
     * Возвращает задачу из корзины (3.5). Права те же, что и на удаление: кто мог отправить
     * в корзину, тот может и достать.
     */
    @Transactional
    public TaskResponse restore(User currentUser, UUID taskId) {
        TaskRepository.DeletedTask deleted = taskRepository.findDeleted(taskId)
                .orElseThrow(TaskNotFoundException::new);
        Project project = projectAccessService.findProjectOrThrow(deleted.getProjectId());
        ProjectRole role = projectAccessService.requireMembership(project.getId(), currentUser);
        projectAccessService.requireRole(role, ProjectRole.MEMBER);

        // Подзадача под удалённым родителем восстановлению не подлежит: возвращать её
        // некуда, она повисла бы под невидимой задачей. Сначала родитель.
        if (deleted.getParentDeletedAt() != null) {
            throw new ParentTaskDeletedException();
        }

        taskRepository.restore(taskId, deleted.getDeletedAt());
        activityService.record(project, currentUser, "task_restored", null,
                Map.of("taskNumber", deleted.getTaskNumber(), "title", deleted.getTitle()));

        Task task = findTaskOrThrow(taskId);
        return TaskResponse.from(task, timeLogRepository.sumHoursByTaskId(taskId), openBlockerCount(taskId));
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> listSubtasks(User currentUser, UUID parentTaskId) {
        Task parent = findTaskOrThrow(parentTaskId);
        projectAccessService.requireMembership(parent.getProject().getId(), currentUser);
        List<Task> subtasks = taskRepository.findByParentTaskIdOrderByPositionAsc(parentTaskId);
        return toResponses(subtasks);
    }

    @Transactional
    public TaskResponse createSubtask(User currentUser, UUID parentTaskId, CreateTaskRequest request) {
        Task parent = findTaskOrThrow(parentTaskId);
        UUID projectId = parent.getProject().getId();
        ProjectRole role = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(role, ProjectRole.MEMBER);

        Task task = new Task();
        task.setProject(parent.getProject());
        task.setParentTask(parent);
        task.setTaskNumber(projectRepository.reserveNextTaskNumber(projectId));
        applyCommonFields(task, task.getProject(), currentUser, request.title(), request.description(),
                request.assigneeId(), request.dueDate(), request.tagId(), request.category());
        TaskUrgency urgency = request.urgency() != null ? request.urgency() : TaskUrgency.MEDIUM;
        task.setUrgency(urgency);
        TaskStatus status = request.status() != null ? request.status() : TaskStatus.NEW;
        task.setStatus(status);
        task.setCreatedBy(currentUser);
        task.setPosition(nextPosition(projectId, status));
        taskRepository.save(task);
        activityService.record(parent.getProject(), currentUser, "task_created", task,
                Map.of("taskNumber", task.getTaskNumber(), "title", task.getTitle()));
        notificationService.notifyTaskAssigned(task, currentUser, task.getAssignee());
        return TaskResponse.from(task, timeLogRepository.sumHoursByTaskId(task.getId()), 0);
    }

    private static final int MY_ACTIVE_TASKS_PAGE_SIZE = 8;
    private static final List<TaskStatus> INACTIVE_STATUSES = TaskStatus.INACTIVE;
    // Задачи с дедлайном внутри этого окна (включая уже просроченные) поднимаются в списке
    // "моих активных задач" выше вообще всего, независимо от urgency — см. findActiveByAssignee.
    // Совпадает с порогом на фронтенде, при котором карточка подсвечивается красным
    // (ActiveTaskCard.DUE_SOON_THRESHOLD_MS) — а не с порогом перехода на часовой отсчёт (тот
    // отдельный, более узкий: меньше суток, см. ActiveTaskCard.isLessThanADay).
    private static final Duration URGENT_DUE_WINDOW = Duration.ofDays(3);

    @Transactional(readOnly = true)
    public PageResponse<MyActiveTaskResponse> listMyActiveTasks(User currentUser, int page) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), MY_ACTIVE_TASKS_PAGE_SIZE);
        Instant urgentCutoff = Instant.now().plus(URGENT_DUE_WINDOW);
        Page<Task> result = taskRepository.findActiveByAssignee(currentUser.getId(), INACTIVE_STATUSES, urgentCutoff, pageable);
        Map<UUID, BigDecimal> hoursByTask = loadHoursTotals(result.getContent().stream().map(Task::getId).toList());
        List<MyActiveTaskResponse> items = result.getContent().stream()
                .map(t -> MyActiveTaskResponse.from(t, hoursByTask.getOrDefault(t.getId(), BigDecimal.ZERO)))
                .toList();
        return PageResponse.from(new PageImpl<>(items, pageable, result.getTotalElements()));
    }

    private void applyCommonFields(Task task, Project project, User currentUser, String title, String description,
                                    UUID assigneeId, Instant dueDate, UUID tagId, String category) {
        UUID projectId = project.getId();
        task.setTitle(title);
        task.setDescription(description);
        task.setAssignee(resolveAssignee(projectId, assigneeId));
        task.setDueDate(dueDate);
        task.setTag(resolveTag(projectId, tagId));
        // Категория приходит именем, а не id: в форме задачи это по-прежнему свободный ввод,
        // и незнакомое имя заводит новую запись справочника (см. CategoryService.resolveOrCreate).
        task.setCategory(categoryService.resolveOrCreate(project, currentUser, category));
    }

    private Task findTaskOrThrow(UUID taskId) {
        return taskRepository.findById(taskId).orElseThrow(TaskNotFoundException::new);
    }

    private int nextPosition(UUID projectId, TaskStatus status) {
        return taskRepository.findMaxPositionForStatus(projectId, status) + 1;
    }

    private User resolveAssignee(UUID projectId, UUID assigneeId) {
        if (assigneeId == null) {
            return null;
        }
        ProjectMember assigneeMembership = projectMemberRepository.findByProjectIdAndUserId(projectId, assigneeId)
                .orElseThrow(AssigneeNotProjectMemberException::new);
        return assigneeMembership.getUser();
    }

    private Tag resolveTag(UUID projectId, UUID tagId) {
        if (tagId == null) {
            return null;
        }
        Tag tag = tagRepository.findById(tagId).orElseThrow(TagNotFoundException::new);
        if (!tag.getProject().getId().equals(projectId)) {
            throw new TagProjectMismatchException();
        }
        return tag;
    }

    // ---------------------------------------------- зависимости между задачами (4.8)

    /**
     * Предупреждение при закрытии задачи, у которой ещё открыты блокеры.
     *
     * <p>Проверяется только переход <b>в</b> DONE: задача, уже стоящая в этом статусе,
     * перетаскивается внутри своей колонки и правится по описанию сколько угодно — отказ
     * там означал бы, что закрытую задачу с блокером нельзя больше вообще тронуть.
     *
     * <p>REJECTED сюда не входит намеренно, хотя формально это тоже «закрыта». «Отклонена»
     * означает, что работу решили не делать, и незакрытый блокер этому не противоречит:
     * задача не выполнена — от неё отказались, и требовать сначала доделать то, что ей
     * мешало, было бы прямо наоборот.
     *
     * <p>Запрет мягкий: {@code ignoreBlockers} проводит ту же правку без вопросов. Смысл в
     * том, чтобы показать препятствие тому, кто его не видит, а не спорить с тем, кто видит.
     */
    private void requireBlockersClosed(Task task, TaskStatus newStatus, boolean ignoreBlockers) {
        if (ignoreBlockers || newStatus != TaskStatus.DONE || task.getStatus() == TaskStatus.DONE) {
            return;
        }
        List<Task> openBlockers = taskDependencyRepository.findOpenBlockers(task.getId(), INACTIVE_STATUSES);
        if (!openBlockers.isEmpty()) {
            throw TaskHasOpenBlockersException.blockedBy(
                    openBlockers.stream().map(Task::getTaskNumber).sorted().toList());
        }
    }

    /**
     * То же для массовой правки — одним запросом на весь набор вместо запроса на задачу.
     * В сообщении перечисляются номера самих заблокированных задач, а не их блокеров: на
     * двадцати задачах список чужих блокеров нечитаем, а вопрос, на который человек здесь
     * отвечает, — «точно закрываем вот эти?».
     */
    private void requireBlockersClosedForAll(List<Task> tasks, TaskStatus newStatus, boolean ignoreBlockers) {
        if (ignoreBlockers || newStatus != TaskStatus.DONE) {
            return;
        }
        List<UUID> closing = tasks.stream()
                .filter(task -> task.getStatus() != TaskStatus.DONE)
                .map(Task::getId)
                .toList();
        Map<UUID, Integer> blocked = loadOpenBlockerCounts(closing);
        if (blocked.isEmpty()) {
            return;
        }
        throw TaskHasOpenBlockersException.blockedTasks(tasks.stream()
                .filter(task -> blocked.containsKey(task.getId()))
                .map(Task::getTaskNumber)
                .sorted()
                .toList());
    }

    /** Незакрытые блокеры одной задачи — для ответов, отдающих её поштучно. */
    private int openBlockerCount(UUID taskId) {
        return loadOpenBlockerCounts(List.of(taskId)).getOrDefault(taskId, 0);
    }

    /**
     * Батч-подсчёт незакрытых блокеров, парный к loadHoursTotals: один запрос на страницу
     * списка. Задачи без блокеров в результат не попадают вовсе — отсутствие ключа и есть
     * ноль, и добавлять их в карту значило бы гонять по проводу колонку из нулей.
     */
    private Map<UUID, Integer> loadOpenBlockerCounts(List<UUID> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return taskDependencyRepository.countOpenBlockers(taskIds, INACTIVE_STATUSES).stream()
                .collect(Collectors.toMap(TaskDependencyRepository.OpenBlockerCount::getTaskId,
                        count -> (int) count.getOpenCount()));
    }

    private Map<UUID, BigDecimal> loadHoursTotals(List<UUID> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return timeLogRepository.sumHoursByTaskIds(taskIds).stream()
                .collect(Collectors.toMap(TimeLogRepository.TaskHoursTotal::getTaskId,
                        TimeLogRepository.TaskHoursTotal::getTotalHours));
    }
}
