package com.pmtracker.project_management_backend.task;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.dto.PageResponse;
import com.pmtracker.project_management_backend.common.exception.AssigneeNotProjectMemberException;
import com.pmtracker.project_management_backend.common.exception.InvalidTargetPositionException;
import com.pmtracker.project_management_backend.common.exception.ParentTaskNotFoundException;
import com.pmtracker.project_management_backend.common.exception.ParentTaskProjectMismatchException;
import com.pmtracker.project_management_backend.common.exception.TagNotFoundException;
import com.pmtracker.project_management_backend.common.exception.TagProjectMismatchException;
import com.pmtracker.project_management_backend.common.exception.TaskNotFoundException;
import com.pmtracker.project_management_backend.common.exception.TaskStatusConflictException;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectAccessService;
import com.pmtracker.project_management_backend.project.ProjectMember;
import com.pmtracker.project_management_backend.project.ProjectMemberRepository;
import com.pmtracker.project_management_backend.project.ProjectRepository;
import com.pmtracker.project_management_backend.project.ProjectRole;
import com.pmtracker.project_management_backend.tag.Tag;
import com.pmtracker.project_management_backend.tag.TagRepository;
import com.pmtracker.project_management_backend.task.dto.CreateTaskRequest;
import com.pmtracker.project_management_backend.task.dto.MyActiveTaskResponse;
import com.pmtracker.project_management_backend.task.dto.TaskResponse;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ProjectAccessService projectAccessService;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectRepository projectRepository;
    private final TagRepository tagRepository;
    private final TimeLogRepository timeLogRepository;

    public TaskService(TaskRepository taskRepository,
                        ProjectAccessService projectAccessService,
                        ProjectMemberRepository projectMemberRepository,
                        ProjectRepository projectRepository,
                        TagRepository tagRepository,
                        TimeLogRepository timeLogRepository) {
        this.taskRepository = taskRepository;
        this.projectAccessService = projectAccessService;
        this.projectMemberRepository = projectMemberRepository;
        this.projectRepository = projectRepository;
        this.tagRepository = tagRepository;
        this.timeLogRepository = timeLogRepository;
    }

    @Transactional
    public TaskResponse create(User currentUser, UUID projectId, CreateTaskRequest request) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        ProjectMember membership = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(membership, ProjectRole.MEMBER);

        Task task = new Task();
        task.setProject(project);
        task.setParentTask(null);
        task.setTaskNumber(projectRepository.reserveNextTaskNumber(projectId));
        applyCommonFields(task, projectId, request.title(), request.description(), request.assigneeId(),
                request.dueDate(), request.tagId());
        TaskUrgency urgency = request.urgency() != null ? request.urgency() : TaskUrgency.MEDIUM;
        task.setUrgency(urgency);
        TaskStatus status = request.status() != null ? request.status() : TaskStatus.NEW;
        task.setStatus(status);
        task.setCreatedBy(currentUser);
        task.setPosition(nextPosition(projectId, status));
        taskRepository.save(task);
        return TaskResponse.from(task, timeLogRepository.sumHoursByTaskId(task.getId()));
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> list(User currentUser, UUID projectId, TaskStatus status, UUID assigneeId, UUID parentId) {
        projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);

        List<Task> tasks;
        if (parentId != null) {
            Task parent = taskRepository.findById(parentId).orElseThrow(ParentTaskNotFoundException::new);
            if (!parent.getProject().getId().equals(projectId)) {
                throw new ParentTaskProjectMismatchException();
            }
            tasks = taskRepository.findByParent(projectId, parentId, status, assigneeId);
        } else {
            tasks = taskRepository.findTopLevel(projectId, status, assigneeId);
        }
        Map<UUID, BigDecimal> hoursByTask = loadHoursTotals(tasks.stream().map(Task::getId).toList());
        return tasks.stream()
                .map(t -> TaskResponse.from(t, hoursByTask.getOrDefault(t.getId(), BigDecimal.ZERO)))
                .toList();
    }

    @Transactional(readOnly = true)
    public TaskResponse getById(User currentUser, UUID taskId) {
        Task task = findTaskOrThrow(taskId);
        projectAccessService.requireMembership(task.getProject().getId(), currentUser);
        return TaskResponse.from(task, timeLogRepository.sumHoursByTaskId(taskId));
    }

    @Transactional
    public TaskResponse update(User currentUser, UUID taskId, UpdateTaskRequest request) {
        Task task = findTaskOrThrow(taskId);
        UUID projectId = task.getProject().getId();
        ProjectMember membership = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(membership, ProjectRole.MEMBER);

        applyCommonFields(task, projectId, request.title(), request.description(), request.assigneeId(),
                request.dueDate(), request.tagId());
        task.setStatus(request.status());
        task.setUrgency(request.urgency());
        taskRepository.save(task);
        return TaskResponse.from(task, timeLogRepository.sumHoursByTaskId(taskId));
    }

    @Transactional
    public TaskResponse updateStatus(User currentUser, UUID taskId, UpdateTaskStatusRequest request) {
        Task task = findTaskOrThrow(taskId);
        UUID projectId = task.getProject().getId();
        ProjectMember membership = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(membership, ProjectRole.MEMBER);

        TaskStatus oldStatus = task.getStatus();
        if (oldStatus != request.expectedStatus()) {
            throw new TaskStatusConflictException();
        }

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
        }

        return TaskResponse.from(task, timeLogRepository.sumHoursByTaskId(taskId));
    }

    private void renumber(List<Task> orderedColumn) {
        for (int i = 0; i < orderedColumn.size(); i++) {
            orderedColumn.get(i).setPosition(i);
        }
    }

    @Transactional
    public void delete(User currentUser, UUID taskId) {
        Task task = findTaskOrThrow(taskId);
        ProjectMember membership = projectAccessService.requireMembership(task.getProject().getId(), currentUser);
        projectAccessService.requireRole(membership, ProjectRole.MEMBER);
        taskRepository.deleteById(taskId);
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> listSubtasks(User currentUser, UUID parentTaskId) {
        Task parent = findTaskOrThrow(parentTaskId);
        projectAccessService.requireMembership(parent.getProject().getId(), currentUser);
        List<Task> subtasks = taskRepository.findByParentTaskIdOrderByPositionAsc(parentTaskId);
        Map<UUID, BigDecimal> hoursByTask = loadHoursTotals(subtasks.stream().map(Task::getId).toList());
        return subtasks.stream()
                .map(t -> TaskResponse.from(t, hoursByTask.getOrDefault(t.getId(), BigDecimal.ZERO)))
                .toList();
    }

    @Transactional
    public TaskResponse createSubtask(User currentUser, UUID parentTaskId, CreateTaskRequest request) {
        Task parent = findTaskOrThrow(parentTaskId);
        UUID projectId = parent.getProject().getId();
        ProjectMember membership = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(membership, ProjectRole.MEMBER);

        Task task = new Task();
        task.setProject(parent.getProject());
        task.setParentTask(parent);
        task.setTaskNumber(projectRepository.reserveNextTaskNumber(projectId));
        applyCommonFields(task, projectId, request.title(), request.description(), request.assigneeId(),
                request.dueDate(), request.tagId());
        TaskUrgency urgency = request.urgency() != null ? request.urgency() : TaskUrgency.MEDIUM;
        task.setUrgency(urgency);
        TaskStatus status = request.status() != null ? request.status() : TaskStatus.NEW;
        task.setStatus(status);
        task.setCreatedBy(currentUser);
        task.setPosition(nextPosition(projectId, status));
        taskRepository.save(task);
        return TaskResponse.from(task, timeLogRepository.sumHoursByTaskId(task.getId()));
    }

    private static final int MY_ACTIVE_TASKS_PAGE_SIZE = 8;
    private static final List<TaskStatus> INACTIVE_STATUSES = List.of(TaskStatus.DONE, TaskStatus.REJECTED);
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

    private void applyCommonFields(Task task, UUID projectId, String title, String description, UUID assigneeId,
                                    Instant dueDate, UUID tagId) {
        task.setTitle(title);
        task.setDescription(description);
        task.setAssignee(resolveAssignee(projectId, assigneeId));
        task.setDueDate(dueDate);
        task.setTag(resolveTag(projectId, tagId));
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

    private Map<UUID, BigDecimal> loadHoursTotals(List<UUID> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return timeLogRepository.sumHoursByTaskIds(taskIds).stream()
                .collect(Collectors.toMap(TimeLogRepository.TaskHoursTotal::getTaskId,
                        TimeLogRepository.TaskHoursTotal::getTotalHours));
    }
}
