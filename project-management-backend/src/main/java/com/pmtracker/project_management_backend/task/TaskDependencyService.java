package com.pmtracker.project_management_backend.task;

import com.pmtracker.project_management_backend.activity.ActivityService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.exception.DuplicateTaskDependencyException;
import com.pmtracker.project_management_backend.common.exception.SelfTaskDependencyException;
import com.pmtracker.project_management_backend.common.exception.TaskDependencyCycleException;
import com.pmtracker.project_management_backend.common.exception.TaskDependencyNotFoundException;
import com.pmtracker.project_management_backend.common.exception.TaskDependencyProjectMismatchException;
import com.pmtracker.project_management_backend.common.exception.TaskNotFoundException;
import com.pmtracker.project_management_backend.project.ProjectAccessService;
import com.pmtracker.project_management_backend.project.ProjectRole;
import com.pmtracker.project_management_backend.task.dto.TaskDependenciesResponse;
import com.pmtracker.project_management_backend.task.dto.TaskLinkResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Зависимости между задачами (4.8): «блокирует» / «заблокирована».
 *
 * <p>Права те же, что у самой задачи: читать связи может любой участник, включая VIEWER,
 * заводить и снимать — MEMBER и выше. Связь меняет порядок работ в проекте и видна всем,
 * поэтому, в отличие от сохранённых представлений (4.7), VIEWER её не заводит.
 *
 * <p>Проверка на закрытость блокеров при переводе в DONE живёт не здесь, а в
 * {@link TaskService}: она срабатывает на смене статуса, а не на правке связей, и
 * ходит в {@link TaskDependencyRepository} напрямую — так у двух сервисов нет взаимной
 * ссылки друг на друга.
 */
@Service
public class TaskDependencyService {

    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final ProjectAccessService projectAccessService;
    private final ActivityService activityService;

    public TaskDependencyService(TaskRepository taskRepository,
                                 TaskDependencyRepository taskDependencyRepository,
                                 ProjectAccessService projectAccessService,
                                 ActivityService activityService) {
        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
        this.projectAccessService = projectAccessService;
        this.activityService = activityService;
    }

    @Transactional(readOnly = true)
    public TaskDependenciesResponse list(User currentUser, UUID taskId) {
        Task task = findTaskOrThrow(taskId);
        projectAccessService.requireMembership(task.getProject().getId(), currentUser);
        return loadDependencies(taskId);
    }

    /**
     * Заводит связь «задача из URL заблокирована задачей blockerTaskId».
     *
     * <p>Порядок проверок — от самой понятной ошибки к самой дорогой: сначала «сама себя»
     * и «из другого проекта» (обе видны по загруженным задачам), потом дубль (один
     * SELECT по уникальному ключу), и только в конце обход графа. Обратный порядок гонял
     * бы BFS ради запроса, который всё равно отклонят.
     */
    @Transactional
    public TaskDependenciesResponse add(User currentUser, UUID taskId, UUID blockerTaskId) {
        Task blocked = findTaskOrThrow(taskId);
        UUID projectId = blocked.getProject().getId();
        ProjectRole role = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireWriteRole(blocked.getProject(), role, ProjectRole.MEMBER);

        if (taskId.equals(blockerTaskId)) {
            throw new SelfTaskDependencyException();
        }

        Task blocker = findTaskOrThrow(blockerTaskId);
        // Проверка на проект, а не на членство в проекте блокера: участник этого проекта
        // может не состоять в соседнем, и «нельзя, вы не участник» было бы неправдой —
        // связывать задачи разных проектов нельзя никому.
        if (!blocker.getProject().getId().equals(projectId)) {
            throw new TaskDependencyProjectMismatchException();
        }

        if (taskDependencyRepository.existsByBlockerIdAndBlockedId(blockerTaskId, taskId)) {
            throw new DuplicateTaskDependencyException();
        }
        requireNoCycle(taskId, blockerTaskId);

        TaskDependency dependency = new TaskDependency();
        dependency.setBlocker(blocker);
        dependency.setBlocked(blocked);
        dependency.setCreatedBy(currentUser);
        taskDependencyRepository.save(dependency);

        recordLinkEvent("task_dependency_added", currentUser, blocked, blocker);
        return loadDependencies(taskId);
    }

    @Transactional
    public TaskDependenciesResponse remove(User currentUser, UUID taskId, UUID blockerTaskId) {
        Task blocked = findTaskOrThrow(taskId);
        ProjectRole role = projectAccessService.requireMembership(blocked.getProject().getId(), currentUser);
        projectAccessService.requireWriteRole(blocked.getProject(), role, ProjectRole.MEMBER);

        if (taskDependencyRepository.deleteLink(blockerTaskId, taskId) == 0) {
            throw new TaskDependencyNotFoundException();
        }

        // Блокер читается уже после удаления связи и только ради номера с названием для
        // ленты. Его может не быть видно (уехал в корзину вместе со своей стороной связи) —
        // тогда событие пишется без него: «связь сняли» — факт о задаче из URL, и терять
        // его из-за того, что вторая сторона в корзине, незачем.
        taskRepository.findById(blockerTaskId)
                .ifPresent(blocker -> recordLinkEvent("task_dependency_removed", currentUser, blocked, blocker));
        return loadDependencies(taskId);
    }

    private TaskDependenciesResponse loadDependencies(UUID taskId) {
        return new TaskDependenciesResponse(
                toLinks(taskDependencyRepository.findBlockers(taskId)),
                toLinks(taskDependencyRepository.findBlocked(taskId)));
    }

    private static List<TaskLinkResponse> toLinks(List<Task> tasks) {
        return tasks.stream().map(TaskLinkResponse::from).toList();
    }

    /**
     * Событие ленты — на задачу из URL, с номером и названием второй стороны в payload.
     * Одно событие, а не два (на обе задачи): факт один, и вторая запись отличалась бы от
     * первой только тем, с какой стороны на него смотреть. Смотреть на связь из карточки
     * блокера незачем — она у него в панели зависимостей и так видна.
     */
    private void recordLinkEvent(String type, User actor, Task blocked, Task blocker) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskNumber", blocked.getTaskNumber());
        payload.put("title", blocked.getTitle());
        payload.put("blockerNumber", blocker.getTaskNumber());
        payload.put("blockerTitle", blocker.getTitle());
        activityService.record(blocked.getProject(), actor, type, blocked, payload);
    }

    /**
     * Отказывает в связи, которая замкнула бы граф блокеров в кольцо: набор задач, ни одну
     * из которых нельзя закрыть первой. Такое кольцо не ломает базу, но делает
     * предупреждение при переводе в DONE вечным — то есть ровно бесполезным.
     *
     * <p>Ищется путь «blocked блокирует ... блокирует blocker»: если он есть, новое ребро
     * (blocker → blocked) его замкнёт. Обход в ширину по рёбрам, а не рекурсивный CTE в SQL:
     * запросов получается столько, какова глубина цепочки, а цепочки блокеров в живом
     * проекте — это единицы звеньев, не сотни. {@code visited} нужен не только ради
     * скорости: если кольцо каким-то образом уже есть в данных (например, приехало
     * миграцией из другого трекера), обход без него не закончится никогда.
     */
    private void requireNoCycle(UUID blockedId, UUID blockerId) {
        Set<UUID> visited = new HashSet<>();
        List<UUID> frontier = List.of(blockedId);
        while (!frontier.isEmpty()) {
            List<UUID> next = new ArrayList<>();
            for (UUID id : taskDependencyRepository.findBlockedIds(frontier)) {
                if (id.equals(blockerId)) {
                    throw new TaskDependencyCycleException();
                }
                if (visited.add(id)) {
                    next.add(id);
                }
            }
            frontier = next;
        }
    }

    private Task findTaskOrThrow(UUID taskId) {
        return taskRepository.findById(taskId).orElseThrow(TaskNotFoundException::new);
    }
}
