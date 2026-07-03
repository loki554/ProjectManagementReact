package com.pmtracker.project_management_backend.task;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.exception.AssigneeNotProjectMemberException;
import com.pmtracker.project_management_backend.common.exception.InvalidTargetPositionException;
import com.pmtracker.project_management_backend.common.exception.ParentTaskNotFoundException;
import com.pmtracker.project_management_backend.common.exception.ParentTaskProjectMismatchException;
import com.pmtracker.project_management_backend.common.exception.TaskNotFoundException;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectAccessService;
import com.pmtracker.project_management_backend.project.ProjectMember;
import com.pmtracker.project_management_backend.project.ProjectMemberRepository;
import com.pmtracker.project_management_backend.project.ProjectRole;
import com.pmtracker.project_management_backend.task.dto.CreateTaskRequest;
import com.pmtracker.project_management_backend.task.dto.TaskResponse;
import com.pmtracker.project_management_backend.task.dto.UpdateTaskRequest;
import com.pmtracker.project_management_backend.task.dto.UpdateTaskStatusRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ProjectAccessService projectAccessService;
    private final ProjectMemberRepository projectMemberRepository;

    public TaskService(TaskRepository taskRepository,
                        ProjectAccessService projectAccessService,
                        ProjectMemberRepository projectMemberRepository) {
        this.taskRepository = taskRepository;
        this.projectAccessService = projectAccessService;
        this.projectMemberRepository = projectMemberRepository;
    }

    @Transactional
    public TaskResponse create(User currentUser, UUID projectId, CreateTaskRequest request) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        ProjectMember membership = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(membership, ProjectRole.MEMBER);

        Task task = new Task();
        task.setProject(project);
        task.setParentTask(null);
        applyCommonFields(task, projectId, request.title(), request.description(), request.assigneeId());
        TaskStatus status = request.status() != null ? request.status() : TaskStatus.NEW;
        task.setStatus(status);
        task.setCreatedBy(currentUser);
        task.setPosition(nextPosition(projectId, status));
        taskRepository.save(task);
        return TaskResponse.from(task);
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
        return tasks.stream().map(TaskResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public TaskResponse getById(User currentUser, UUID taskId) {
        Task task = findTaskOrThrow(taskId);
        projectAccessService.requireMembership(task.getProject().getId(), currentUser);
        return TaskResponse.from(task);
    }

    @Transactional
    public TaskResponse update(User currentUser, UUID taskId, UpdateTaskRequest request) {
        Task task = findTaskOrThrow(taskId);
        UUID projectId = task.getProject().getId();
        ProjectMember membership = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(membership, ProjectRole.MEMBER);

        applyCommonFields(task, projectId, request.title(), request.description(), request.assigneeId());
        task.setStatus(request.status());
        taskRepository.save(task);
        return TaskResponse.from(task);
    }

    @Transactional
    public TaskResponse updateStatus(User currentUser, UUID taskId, UpdateTaskStatusRequest request) {
        Task task = findTaskOrThrow(taskId);
        UUID projectId = task.getProject().getId();
        ProjectMember membership = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(membership, ProjectRole.MEMBER);

        UUID parentId = task.getParentTask() != null ? task.getParentTask().getId() : null;
        TaskStatus oldStatus = task.getStatus();
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

        return TaskResponse.from(task);
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
        return taskRepository.findByParentTaskIdOrderByPositionAsc(parentTaskId).stream()
                .map(TaskResponse::from)
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
        applyCommonFields(task, projectId, request.title(), request.description(), request.assigneeId());
        TaskStatus status = request.status() != null ? request.status() : TaskStatus.NEW;
        task.setStatus(status);
        task.setCreatedBy(currentUser);
        task.setPosition(nextPosition(projectId, status));
        taskRepository.save(task);
        return TaskResponse.from(task);
    }

    private void applyCommonFields(Task task, UUID projectId, String title, String description, UUID assigneeId) {
        task.setTitle(title);
        task.setDescription(description);
        task.setAssignee(resolveAssignee(projectId, assigneeId));
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
}
