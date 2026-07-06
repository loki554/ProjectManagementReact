package com.pmtracker.project_management_backend.activity;

import com.pmtracker.project_management_backend.activity.dto.ActivityResponse;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.dto.PageResponse;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectAccessService;
import com.pmtracker.project_management_backend.task.Task;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class ActivityService {

    private static final int PAGE_SIZE = 20;

    private final ProjectActivityRepository projectActivityRepository;
    private final ProjectAccessService projectAccessService;

    public ActivityService(ProjectActivityRepository projectActivityRepository,
                           ProjectAccessService projectAccessService) {
        this.projectActivityRepository = projectActivityRepository;
        this.projectAccessService = projectAccessService;
    }

    /**
     * Запись события. Вызывается напрямую из доменных сервисов (TaskService и т.д.)
     * в их же транзакции — откат действия откатывает и запись в ленте; Spring events
     * не используются сознательно (см. план, фаза 6). Права здесь не проверяются:
     * это внутренний API, авторизацию уже выполнил вызывающий сервис.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Project project, User actor, String type, Task task, Map<String, Object> payload) {
        ProjectActivity activity = new ProjectActivity();
        activity.setProject(project);
        activity.setActor(actor);
        activity.setType(type);
        activity.setTask(task);
        activity.setPayload(payload);
        projectActivityRepository.save(activity);
    }

    @Transactional(readOnly = true)
    public PageResponse<ActivityResponse> list(User currentUser, UUID projectId, UUID taskId, int page) {
        projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);
        var pageRequest = PageRequest.of(Math.max(page, 0), PAGE_SIZE);
        var activityPage = taskId != null
                ? projectActivityRepository.findByProjectIdAndTaskIdOrderByCreatedAtDesc(projectId, taskId, pageRequest)
                : projectActivityRepository.findByProjectIdOrderByCreatedAtDesc(projectId, pageRequest);
        return PageResponse.from(activityPage.map(ActivityResponse::from));
    }
}
