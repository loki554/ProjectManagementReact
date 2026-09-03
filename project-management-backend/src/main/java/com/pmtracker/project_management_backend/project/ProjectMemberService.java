package com.pmtracker.project_management_backend.project;

import com.pmtracker.project_management_backend.activity.ActivityService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.exception.AlreadyProjectMemberException;
import com.pmtracker.project_management_backend.common.exception.CannotRemoveLastOwnerException;
import com.pmtracker.project_management_backend.common.exception.ResourceNotFoundException;
import com.pmtracker.project_management_backend.project.dto.MemberResponse;
import com.pmtracker.project_management_backend.project.dto.UpdateMemberRoleRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProjectMemberService {

    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectMembershipCache membershipCache;
    private final ProjectAccessService projectAccessService;
    private final ActivityService activityService;

    public ProjectMemberService(ProjectMemberRepository projectMemberRepository,
                                 ProjectMembershipCache membershipCache,
                                 ProjectAccessService projectAccessService,
                                 ActivityService activityService) {
        this.projectMemberRepository = projectMemberRepository;
        this.membershipCache = membershipCache;
        this.projectAccessService = projectAccessService;
        this.activityService = activityService;
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> list(User currentUser, UUID projectId) {
        projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);

        return projectMemberRepository.findByProjectIdWithUser(projectId).stream()
                .map(MemberResponse::from)
                .toList();
    }

    /**
     * Добавление пользователя в проект. Внутренний метод: прав не проверяет — их проверил
     * вызывающий, — и живёт в транзакции вызывающего (MANDATORY).
     * <p>
     * Вызывается из двух мест {@link ProjectInvitationService}: приглашение уже
     * зарегистрированного (участник появляется сразу) и принятие приглашения тем, кто
     * зарегистрировался по ссылке. Отдельный метод, а не два одинаковых куска: забыть в
     * одном из них сброс кэша (3.10) или запись в ленту — ровно тот класс расхождения,
     * который потом ищут неделю.
     *
     * @param actor кто инициировал добавление — для ленты активности. У приглашения,
     *              принятого самим приглашённым, это он сам: администратор нажал «пригласить»
     *              когда-то давно, а в проект человек вошёл сейчас и своими руками.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    MemberResponse addMember(Project project, User user, ProjectRole role, User actor) {
        if (projectMemberRepository.findByProjectIdAndUserId(project.getId(), user.getId()).isPresent()) {
            throw new AlreadyProjectMemberException();
        }

        ProjectMember member = new ProjectMember();
        member.setProject(project);
        member.setUser(user);
        member.setRole(role);
        projectMemberRepository.save(member);
        // Кэш проверки доступа (3.10) сбрасывается на каждое изменение состава участников,
        // и строго после коммита — иначе параллельный запрос вернёт в него старую роль.
        membershipCache.invalidate(project.getId(), user.getId());

        activityService.record(project, actor, "member_added", null,
                Map.of("userName", displayName(user), "role", role.name()));

        return MemberResponse.from(member);
    }

    @Transactional
    public MemberResponse updateRole(User currentUser, UUID projectId, UUID targetUserId, UpdateMemberRoleRequest request) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        ProjectRole myRole = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(myRole, ProjectRole.ADMIN);

        ProjectMember target = findMemberOrThrow(projectId, targetUserId);

        if (target.getRole() == ProjectRole.OWNER && request.role() != ProjectRole.OWNER) {
            requireAnotherOwnerExists(projectId);
        }

        ProjectRole oldRole = target.getRole();
        target.setRole(request.role());
        projectMemberRepository.save(target);
        membershipCache.invalidate(projectId, targetUserId);

        // Повторный сабмит той же роли — не событие.
        if (oldRole != request.role()) {
            activityService.record(project, currentUser, "member_role_changed", null,
                    Map.of("userName", displayName(target.getUser()),
                            "oldRole", oldRole.name(), "newRole", request.role().name()));
        }
        return MemberResponse.from(target);
    }

    @Transactional
    public void remove(User currentUser, UUID projectId, UUID targetUserId) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        ProjectRole myRole = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(myRole, ProjectRole.ADMIN);

        ProjectMember target = findMemberOrThrow(projectId, targetUserId);

        if (target.getRole() == ProjectRole.OWNER) {
            requireAnotherOwnerExists(projectId);
        }

        projectMemberRepository.delete(target);
        membershipCache.invalidate(projectId, targetUserId);
        activityService.record(project, currentUser, "member_removed", null,
                Map.of("userName", displayName(target.getUser())));
    }

    private ProjectMember findMemberOrThrow(UUID projectId, UUID userId) {
        return projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
    }

    private static String displayName(User user) {
        return user.getLastName() + " " + user.getFirstName();
    }

    private void requireAnotherOwnerExists(UUID projectId) {
        if (projectMemberRepository.countByProjectIdAndRole(projectId, ProjectRole.OWNER) <= 1) {
            throw new CannotRemoveLastOwnerException();
        }
    }
}
