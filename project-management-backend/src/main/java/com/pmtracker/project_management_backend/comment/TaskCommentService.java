package com.pmtracker.project_management_backend.comment;

import com.pmtracker.project_management_backend.activity.ActivityService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.comment.dto.CommentResponse;
import com.pmtracker.project_management_backend.comment.dto.CreateCommentRequest;
import com.pmtracker.project_management_backend.common.exception.CommentNotFoundException;
import com.pmtracker.project_management_backend.common.exception.NotCommentOwnerException;
import com.pmtracker.project_management_backend.common.exception.TaskNotFoundException;
import com.pmtracker.project_management_backend.project.ProjectAccessService;
import com.pmtracker.project_management_backend.project.ProjectMember;
import com.pmtracker.project_management_backend.project.ProjectRole;
import com.pmtracker.project_management_backend.task.Task;
import com.pmtracker.project_management_backend.task.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TaskCommentService {

    public static final String SORT_OLDEST = "oldest";

    private final TaskCommentRepository taskCommentRepository;
    private final TaskRepository taskRepository;
    private final ProjectAccessService projectAccessService;
    private final ActivityService activityService;

    public TaskCommentService(TaskCommentRepository taskCommentRepository,
                               TaskRepository taskRepository,
                               ProjectAccessService projectAccessService,
                               ActivityService activityService) {
        this.taskCommentRepository = taskCommentRepository;
        this.taskRepository = taskRepository;
        this.projectAccessService = projectAccessService;
        this.activityService = activityService;
    }

    @Transactional
    public CommentResponse create(User currentUser, UUID taskId, CreateCommentRequest request) {
        Task task = findTaskOrThrow(taskId);
        ProjectMember membership = projectAccessService.requireMembership(task.getProject().getId(), currentUser);
        projectAccessService.requireRole(membership, ProjectRole.MEMBER);

        TaskComment comment = new TaskComment();
        comment.setTask(task);
        comment.setAuthor(currentUser);
        comment.setBody(request.body());
        taskCommentRepository.save(comment);

        activityService.record(task.getProject(), currentUser, "comment_added", task,
                Map.of("taskNumber", task.getTaskNumber(), "title", task.getTitle()));

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

    @Transactional
    public void delete(User currentUser, UUID commentId) {
        TaskComment comment = taskCommentRepository.findById(commentId).orElseThrow(CommentNotFoundException::new);
        UUID projectId = comment.getTask().getProject().getId();
        ProjectMember membership = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(membership, ProjectRole.MEMBER);

        boolean isAuthor = comment.getAuthor().getId().equals(currentUser.getId());
        boolean isModerator = membership.getRole().isAtLeast(ProjectRole.ADMIN);
        if (!isAuthor && !isModerator) {
            throw new NotCommentOwnerException();
        }

        taskCommentRepository.delete(comment);
    }

    private Task findTaskOrThrow(UUID taskId) {
        return taskRepository.findById(taskId).orElseThrow(TaskNotFoundException::new);
    }
}
