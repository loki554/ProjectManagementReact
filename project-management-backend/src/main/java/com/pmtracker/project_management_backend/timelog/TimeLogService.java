package com.pmtracker.project_management_backend.timelog;

import com.pmtracker.project_management_backend.activity.ActivityService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.exception.NotTimeLogOwnerException;
import com.pmtracker.project_management_backend.common.exception.TaskNotFoundException;
import com.pmtracker.project_management_backend.common.exception.TimeLogNotFoundException;
import com.pmtracker.project_management_backend.project.ProjectAccessService;
import com.pmtracker.project_management_backend.project.ProjectMember;
import com.pmtracker.project_management_backend.project.ProjectRole;
import com.pmtracker.project_management_backend.task.Task;
import com.pmtracker.project_management_backend.task.TaskRepository;
import com.pmtracker.project_management_backend.timelog.dto.CreateTimeLogRequest;
import com.pmtracker.project_management_backend.timelog.dto.TimeLogResponse;
import com.pmtracker.project_management_backend.timelog.dto.TimeLogsResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class TimeLogService {

    private final TimeLogRepository timeLogRepository;
    private final TaskRepository taskRepository;
    private final ProjectAccessService projectAccessService;
    private final ActivityService activityService;

    public TimeLogService(TimeLogRepository timeLogRepository,
                           TaskRepository taskRepository,
                           ProjectAccessService projectAccessService,
                           ActivityService activityService) {
        this.timeLogRepository = timeLogRepository;
        this.taskRepository = taskRepository;
        this.projectAccessService = projectAccessService;
        this.activityService = activityService;
    }

    @Transactional
    public TimeLogResponse create(User currentUser, UUID taskId, CreateTimeLogRequest request) {
        Task task = findTaskOrThrow(taskId);
        ProjectMember membership = projectAccessService.requireMembership(task.getProject().getId(), currentUser);
        projectAccessService.requireRole(membership, ProjectRole.MEMBER);

        TimeLog timeLog = new TimeLog();
        timeLog.setTask(task);
        timeLog.setUser(currentUser);
        timeLog.setHours(request.hours());
        timeLog.setSpentOn(request.spentOn());
        timeLog.setDescription(request.description());
        timeLogRepository.save(timeLog);
        // hours — BigDecimal, в jsonb уедет числом; фронтенд показывает как есть.
        activityService.record(task.getProject(), currentUser, "time_logged", task,
                Map.of("taskNumber", task.getTaskNumber(), "title", task.getTitle(), "hours", request.hours()));
        return TimeLogResponse.from(timeLog);
    }

    @Transactional(readOnly = true)
    public TimeLogsResponse list(User currentUser, UUID taskId) {
        Task task = findTaskOrThrow(taskId);
        projectAccessService.requireMembership(task.getProject().getId(), currentUser);

        var items = timeLogRepository.findByTaskIdOrderBySpentOnDescCreatedAtDesc(taskId).stream()
                .map(TimeLogResponse::from)
                .toList();
        var totalHours = timeLogRepository.sumHoursByTaskId(taskId);
        return new TimeLogsResponse(items, totalHours);
    }

    @Transactional
    public void delete(User currentUser, UUID timeLogId) {
        TimeLog timeLog = timeLogRepository.findById(timeLogId).orElseThrow(TimeLogNotFoundException::new);
        UUID projectId = timeLog.getTask().getProject().getId();
        ProjectMember membership = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(membership, ProjectRole.MEMBER);

        boolean isAuthor = timeLog.getUser().getId().equals(currentUser.getId());
        boolean isModerator = membership.getRole().isAtLeast(ProjectRole.ADMIN);
        if (!isAuthor && !isModerator) {
            throw new NotTimeLogOwnerException();
        }

        timeLogRepository.delete(timeLog);
    }

    private Task findTaskOrThrow(UUID taskId) {
        return taskRepository.findById(taskId).orElseThrow(TaskNotFoundException::new);
    }
}
