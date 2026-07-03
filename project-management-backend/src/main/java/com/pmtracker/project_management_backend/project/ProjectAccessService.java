package com.pmtracker.project_management_backend.project;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.exception.InsufficientProjectRoleException;
import com.pmtracker.project_management_backend.common.exception.NotProjectMemberException;
import com.pmtracker.project_management_backend.common.exception.ProjectNotFoundException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Общая точка проверки "проект существует / пользователь его участник / роль участника
 * достаточна" — используется и ProjectService (CRUD проекта), и ProjectMemberService
 * (управление участниками), чтобы не дублировать логику ролей в двух местах.
 */
@Service
public class ProjectAccessService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;

    public ProjectAccessService(ProjectRepository projectRepository, ProjectMemberRepository projectMemberRepository) {
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
    }

    public Project findProjectOrThrow(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(ProjectNotFoundException::new);
    }

    public ProjectMember requireMembership(UUID projectId, User user) {
        return projectMemberRepository.findByProjectIdAndUserId(projectId, user.getId())
                .orElseThrow(NotProjectMemberException::new);
    }

    public void requireRole(ProjectMember membership, ProjectRole required) {
        if (!membership.getRole().isAtLeast(required)) {
            throw new InsufficientProjectRoleException();
        }
    }
}
