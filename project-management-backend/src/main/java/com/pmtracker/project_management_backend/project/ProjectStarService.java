package com.pmtracker.project_management_backend.project;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.project.dto.ProjectStarResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ProjectStarService {

    private final ProjectStarRepository projectStarRepository;
    private final ProjectAccessService projectAccessService;

    public ProjectStarService(ProjectStarRepository projectStarRepository,
                              ProjectAccessService projectAccessService) {
        this.projectStarRepository = projectStarRepository;
        this.projectAccessService = projectAccessService;
    }

    @Transactional(readOnly = true)
    public ProjectStarResponse get(User currentUser, UUID projectId) {
        projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);
        return buildResponse(projectId, currentUser);
    }

    // Звезда — личное действие, поэтому достаточно членства в проекте: VIEWER тоже
    // может отмечать (в отличие от write-операций над задачами/настройками).
    @Transactional
    public ProjectStarResponse star(User currentUser, UUID projectId) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);

        // Идемпотентность: повторный PUT не создаёт дубликат (UNIQUE в V11 — вторая линия).
        if (!projectStarRepository.existsByProjectIdAndUserId(projectId, currentUser.getId())) {
            ProjectStar star = new ProjectStar();
            star.setProject(project);
            star.setUser(currentUser);
            projectStarRepository.save(star);
        }
        return buildResponse(projectId, currentUser);
    }

    @Transactional
    public ProjectStarResponse unstar(User currentUser, UUID projectId) {
        projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);
        projectStarRepository.deleteByProjectIdAndUserId(projectId, currentUser.getId());
        return buildResponse(projectId, currentUser);
    }

    private ProjectStarResponse buildResponse(UUID projectId, User currentUser) {
        return new ProjectStarResponse(
                projectStarRepository.countByProjectId(projectId),
                projectStarRepository.existsByProjectIdAndUserId(projectId, currentUser.getId())
        );
    }
}
