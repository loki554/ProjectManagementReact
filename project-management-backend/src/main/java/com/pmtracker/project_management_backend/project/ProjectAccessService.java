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
    private final ProjectMembershipCache membershipCache;

    public ProjectAccessService(ProjectRepository projectRepository, ProjectMembershipCache membershipCache) {
        this.projectRepository = projectRepository;
        this.membershipCache = membershipCache;
    }

    public Project findProjectOrThrow(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(ProjectNotFoundException::new);
    }

    /**
     * Роль пользователя в проекте, либо 403, если он не участник.
     *
     * <p>Возвращается роль, а не строка участника: вызывающему коду от неё больше ничего и
     * не нужно, а отдавать наружу сущность из проверки доступа — значит однажды получить
     * место, которое эту сущность меняет, и кэш (3.10), раздающий её всем сразу.
     */
    public ProjectRole requireMembership(UUID projectId, User user) {
        return membershipCache.findRole(projectId, user.getId())
                .orElseThrow(NotProjectMemberException::new);
    }

    public void requireRole(ProjectRole role, ProjectRole required) {
        if (!role.isAtLeast(required)) {
            throw new InsufficientProjectRoleException();
        }
    }
}
