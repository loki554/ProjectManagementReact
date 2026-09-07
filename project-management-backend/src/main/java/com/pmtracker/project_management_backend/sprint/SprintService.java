package com.pmtracker.project_management_backend.sprint;

import com.pmtracker.project_management_backend.activity.ActivityService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.exception.DuplicateSprintNameException;
import com.pmtracker.project_management_backend.common.exception.InvalidSprintDatesException;
import com.pmtracker.project_management_backend.common.exception.InvalidSprintTransitionException;
import com.pmtracker.project_management_backend.common.exception.SprintAlreadyActiveException;
import com.pmtracker.project_management_backend.common.exception.SprintCompletedException;
import com.pmtracker.project_management_backend.common.exception.SprintNotFoundException;
import com.pmtracker.project_management_backend.common.exception.SprintProjectMismatchException;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectAccessService;
import com.pmtracker.project_management_backend.project.ProjectRole;
import com.pmtracker.project_management_backend.sprint.dto.CompleteSprintRequest;
import com.pmtracker.project_management_backend.sprint.dto.CompleteSprintResponse;
import com.pmtracker.project_management_backend.sprint.dto.SprintRequest;
import com.pmtracker.project_management_backend.sprint.dto.SprintResponse;
import com.pmtracker.project_management_backend.task.Task;
import com.pmtracker.project_management_backend.task.TaskRepository;
import com.pmtracker.project_management_backend.task.TaskStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Спринты проекта (4.9).
 *
 * <p><b>Про права.</b> Читают спринты все участники, включая VIEWER: план на две недели —
 * то же самое, что список задач, и роль, которая только и делает, что смотрит, обязана его
 * видеть. Заводят, правят, стартуют и закрывают — ADMIN и выше. Это отличается и от тэгов с
 * категориями (там OWNER), и от зависимостей (там MEMBER), и различие намеренное: спринт
 * распоряжается чужой работой — закрытие спринта перекладывает недоделанные задачи всей
 * команды, — поэтому это не правка задачи, которую делает любой исполнитель, но и не
 * настройка проекта, ради которой нужно искать владельца. ADMIN — ровно та роль, которая в
 * этом трекере существует для «ведёт проект, но не владеет им».
 *
 * <p>Положить задачу в спринт и вынуть её оттуда — наоборот, обычная правка задачи (MEMBER
 * и выше) и живёт в TaskService: это поле задачи, такое же как тэг.
 */
@Service
public class SprintService {

    /**
     * Порядок спринтов на странице: сначала активный, потом запланированные (ближайший
     * сверху), потом завершённые (последний сверху). Одним ORDER BY не выражается — у
     * незакрытых нужен ближайший, у закрытых последний, — поэтому сортируется в памяти:
     * спринтов в проекте десятки, а не тысячи.
     */
    private static final Comparator<Sprint> LIST_ORDER = Comparator
            .comparingInt((Sprint sprint) -> switch (sprint.getStatus()) {
                case ACTIVE -> 0;
                case PLANNED -> 1;
                case COMPLETED -> 2;
            })
            .thenComparingLong(sprint -> sprint.getStatus() == SprintStatus.COMPLETED
                    ? -sprint.getStartDate().toEpochDay()
                    : sprint.getStartDate().toEpochDay())
            .thenComparing(Sprint::getName);

    /** «Задача закрыта» — одно определение на весь пункт, см. SprintRepository. */
    private static final List<TaskStatus> CLOSED_STATUSES = TaskStatus.INACTIVE;

    private final SprintRepository sprintRepository;
    private final TaskRepository taskRepository;
    private final ProjectAccessService projectAccessService;
    private final ActivityService activityService;

    public SprintService(SprintRepository sprintRepository,
                         TaskRepository taskRepository,
                         ProjectAccessService projectAccessService,
                         ActivityService activityService) {
        this.sprintRepository = sprintRepository;
        this.taskRepository = taskRepository;
        this.projectAccessService = projectAccessService;
        this.activityService = activityService;
    }

    @Transactional(readOnly = true)
    public List<SprintResponse> list(User currentUser, UUID projectId) {
        projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);

        Map<UUID, SprintRepository.SprintTaskCount> counts = loadCounts(projectId);
        return sprintRepository.findByProjectIdOrderByStartDateAsc(projectId).stream()
                .sorted(LIST_ORDER)
                .map(sprint -> toResponse(sprint, counts))
                .toList();
    }

    @Transactional
    public SprintResponse create(User currentUser, UUID projectId, SprintRequest request) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        requireManageRole(projectId, currentUser);

        String name = normalizeName(request.name());
        if (sprintRepository.existsByProjectIdAndName(projectId, name)) {
            throw new DuplicateSprintNameException();
        }
        requireValidDates(request);

        Sprint sprint = new Sprint();
        sprint.setProject(project);
        sprint.setCreatedBy(currentUser);
        sprint.setStatus(SprintStatus.PLANNED);
        applyFields(sprint, name, request);
        sprintRepository.save(sprint);

        activityService.record(project, currentUser, "sprint_created", null, Map.of("name", sprint.getName()));
        // Только что заведённый спринт пуст — считать нечего, и лишний запрос за нулями не нужен.
        return SprintResponse.from(sprint, 0, 0);
    }

    @Transactional
    public SprintResponse update(User currentUser, UUID sprintId, SprintRequest request) {
        Sprint sprint = findSprintOrThrow(sprintId);
        UUID projectId = sprint.getProject().getId();
        requireManageRole(projectId, currentUser);

        String name = normalizeName(request.name());
        if (!sprint.getName().equals(name) && sprintRepository.existsByProjectIdAndName(projectId, name)) {
            throw new DuplicateSprintNameException();
        }
        requireValidDates(request);

        // Правка карточки завершённого спринта не запрещена: опечатка в названии захода,
        // который закрыли вчера, — не повод заводить новый. Запрещено только менять его
        // состав (см. resolveForTask) и статус — на это есть отдельные действия.
        applyFields(sprint, name, request);
        sprintRepository.save(sprint);
        return toResponse(sprint, loadCounts(projectId));
    }

    /**
     * Начать спринт. Активным в проекте может быть только один — иначе вопрос «чем мы сейчас
     * заняты» перестаёт иметь единственный ответ. Проверка здесь ради внятного 409, но
     * настоящую гарантию даёт частичный уникальный индекс в V31: две одновременные попытки
     * стартовать разные спринты не увидят друг друга в своих транзакциях.
     */
    @Transactional
    public SprintResponse start(User currentUser, UUID sprintId) {
        Sprint sprint = findSprintOrThrow(sprintId);
        UUID projectId = sprint.getProject().getId();
        requireManageRole(projectId, currentUser);

        if (sprint.getStatus() != SprintStatus.PLANNED) {
            throw new InvalidSprintTransitionException();
        }
        sprintRepository.findByProjectIdAndStatus(projectId, SprintStatus.ACTIVE)
                .ifPresent(active -> {
                    throw new SprintAlreadyActiveException();
                });

        sprint.setStatus(SprintStatus.ACTIVE);
        sprint.setStartedAt(Instant.now());
        sprintRepository.save(sprint);

        activityService.record(sprint.getProject(), currentUser, "sprint_started", null,
                Map.of("name", sprint.getName()));
        return toResponse(sprint, loadCounts(projectId));
    }

    /**
     * Завершить спринт и разобраться с тем, что не успели.
     *
     * <p>Незакрытые задачи не остаются в закрытом спринте ни при каком ответе: иначе его
     * прогресс навсегда останется неполным, а сами задачи осядут в архиве, куда никто не
     * заглядывает. Они либо переезжают в указанный незакрытый спринт, либо возвращаются в
     * бэклог — то есть туда же, откуда пришли при планировании.
     *
     * <p>Каждой переехавшей задаче пишется своё событие {@code task_sprint_changed} — то
     * же, что и при ручной смене спринта, и по той же причине, что и в массовой правке
     * (4.6): лента задачи отвечает на вопрос «что с ней происходило», и «выпала из спринта,
     * который закрыли» — часть этого ответа. Событие спринта при этом одно на всё действие,
     * с числом переехавших задач в payload.
     */
    @Transactional
    public CompleteSprintResponse complete(User currentUser, UUID sprintId, CompleteSprintRequest request) {
        Sprint sprint = findSprintOrThrow(sprintId);
        UUID projectId = sprint.getProject().getId();
        requireManageRole(projectId, currentUser);

        if (sprint.getStatus() != SprintStatus.ACTIVE) {
            throw new InvalidSprintTransitionException();
        }

        Sprint target = resolveMoveTarget(sprint, request.moveUnfinishedToSprintId());
        List<Task> unfinished = taskRepository.findBySprintIdAndStatusNotIn(sprintId, CLOSED_STATUSES);
        for (Task task : unfinished) {
            task.setSprint(target);
            recordTaskSprintChange(task, currentUser, sprint.getName(), target != null ? target.getName() : null);
        }

        sprint.setStatus(SprintStatus.COMPLETED);
        sprint.setCompletedAt(Instant.now());
        sprintRepository.save(sprint);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", sprint.getName());
        payload.put("movedTasks", unfinished.size());
        // null допустим («в бэклог»), поэтому LinkedHashMap, а не Map.of.
        payload.put("movedTo", target != null ? target.getName() : null);
        activityService.record(sprint.getProject(), currentUser, "sprint_completed", null, payload);

        return new CompleteSprintResponse(toResponse(sprint, loadCounts(projectId)), unfinished.size());
    }

    /**
     * Удаление спринта. Задачи при этом не трогаются вовсе: ссылка обнуляется на уровне
     * схемы (ON DELETE SET NULL, V31), и весь его состав возвращается в бэклог. Удалять
     * можно спринт в любом статусе, включая завершённый: спринт — это план, а не архив
     * работ, и «мы передумали так планировать» — обычное явление, тогда как задачи и их
     * история переживают удаление целиком.
     */
    @Transactional
    public void delete(User currentUser, UUID sprintId) {
        Sprint sprint = findSprintOrThrow(sprintId);
        requireManageRole(sprint.getProject().getId(), currentUser);

        activityService.record(sprint.getProject(), currentUser, "sprint_deleted", null,
                Map.of("name", sprint.getName()));
        sprintRepository.delete(sprint);
    }

    // ------------------------------------------------------- используется TaskService (4.9)

    /**
     * Спринт задачи по id из запроса. Вызывается из TaskService, права на задачу к этому
     * моменту уже проверены — здесь проверяется только сам спринт.
     *
     * <p>{@code current} — спринт, который у задачи стоит сейчас. Он нужен ради одного
     * случая: у задачи из завершённого спринта правят описание, и форма присылает обратно
     * тот же {@code sprintId}. Отказывать здесь значило бы, что задачу, попавшую в архивный
     * спринт, больше нельзя тронуть вообще. Поэтому «в завершённый спринт нельзя» — это
     * запрет на <i>изменение</i> состава, а не на существование задачи в нём.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Sprint resolveForTask(UUID projectId, UUID sprintId, Sprint current) {
        if (sprintId == null) {
            return null;
        }
        if (current != null && current.getId().equals(sprintId)) {
            return current;
        }
        Sprint sprint = findSprintOrThrow(sprintId);
        if (!sprint.getProject().getId().equals(projectId)) {
            throw new SprintProjectMismatchException();
        }
        if (!sprint.acceptsTasks()) {
            throw new SprintCompletedException();
        }
        return sprint;
    }

    // ------------------------------------------------------------------------- внутреннее

    private Sprint resolveMoveTarget(Sprint completing, UUID targetId) {
        if (targetId == null) {
            return null;
        }
        if (targetId.equals(completing.getId())) {
            // Переложить недоделанное в закрываемый же спринт — это «оставить как есть»,
            // то есть ровно то, чего завершение делать не должно.
            throw new InvalidSprintTransitionException();
        }
        Sprint target = findSprintOrThrow(targetId);
        if (!target.getProject().getId().equals(completing.getProject().getId())) {
            throw new SprintProjectMismatchException();
        }
        if (!target.acceptsTasks()) {
            throw new SprintCompletedException();
        }
        return target;
    }

    private void recordTaskSprintChange(Task task, User actor, String oldName, String newName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskNumber", task.getTaskNumber());
        payload.put("title", task.getTitle());
        payload.put("old", oldName);
        payload.put("new", newName);
        activityService.record(task.getProject(), actor, "task_sprint_changed", task, payload);
    }

    private void requireManageRole(UUID projectId, User currentUser) {
        ProjectRole role = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(role, ProjectRole.ADMIN);
    }

    private static void requireValidDates(SprintRequest request) {
        if (request.endDate().isBefore(request.startDate())) {
            throw new InvalidSprintDatesException();
        }
    }

    private static void applyFields(Sprint sprint, String name, SprintRequest request) {
        sprint.setName(name);
        sprint.setGoal(normalizeGoal(request.goal()));
        sprint.setStartDate(request.startDate());
        sprint.setEndDate(request.endDate());
    }

    private Map<UUID, SprintRepository.SprintTaskCount> loadCounts(UUID projectId) {
        return sprintRepository.countTasksByProjectId(projectId, CLOSED_STATUSES).stream()
                .collect(Collectors.toMap(SprintRepository.SprintTaskCount::getSprintId, count -> count));
    }

    private static SprintResponse toResponse(Sprint sprint, Map<UUID, SprintRepository.SprintTaskCount> counts) {
        SprintRepository.SprintTaskCount count = counts.get(sprint.getId());
        return SprintResponse.from(sprint,
                count != null ? count.getTaskCount() : 0,
                count != null ? count.getClosedTaskCount() : 0);
    }

    private Sprint findSprintOrThrow(UUID sprintId) {
        return sprintRepository.findById(sprintId).orElseThrow(SprintNotFoundException::new);
    }

    private static String normalizeName(String name) {
        return name.trim();
    }

    // Пустая цель — это «цели нет», а не цель из пробелов (тот же приём, что в
    // CategoryService.normalizeName и SavedViewService.normalizeSearch).
    private static String normalizeGoal(String goal) {
        if (goal == null) {
            return null;
        }
        String trimmed = goal.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
