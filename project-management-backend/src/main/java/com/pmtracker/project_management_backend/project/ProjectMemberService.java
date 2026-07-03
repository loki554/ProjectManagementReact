package com.pmtracker.project_management_backend.project;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.UserRepository;
import com.pmtracker.project_management_backend.common.exception.AlreadyProjectMemberException;
import com.pmtracker.project_management_backend.common.exception.CannotRemoveLastOwnerException;
import com.pmtracker.project_management_backend.common.exception.ResourceNotFoundException;
import com.pmtracker.project_management_backend.common.exception.UserNotFoundForInviteException;
import com.pmtracker.project_management_backend.project.dto.InviteMemberRequest;
import com.pmtracker.project_management_backend.project.dto.MemberResponse;
import com.pmtracker.project_management_backend.project.dto.UpdateMemberRoleRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ProjectMemberService {

    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;
    private final ProjectAccessService projectAccessService;

    public ProjectMemberService(ProjectMemberRepository projectMemberRepository,
                                 UserRepository userRepository,
                                 ProjectAccessService projectAccessService) {
        this.projectMemberRepository = projectMemberRepository;
        this.userRepository = userRepository;
        this.projectAccessService = projectAccessService;
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> list(User currentUser, UUID projectId) {
        projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);

        return projectMemberRepository.findByProjectIdOrderByJoinedAtAsc(projectId).stream()
                .map(MemberResponse::from)
                .toList();
    }

    @Transactional
    public MemberResponse invite(User currentUser, UUID projectId, InviteMemberRequest request) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        ProjectMember myMembership = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(myMembership, ProjectRole.ADMIN);

        User invitee = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new UserNotFoundForInviteException(request.email()));

        if (projectMemberRepository.findByProjectIdAndUserId(projectId, invitee.getId()).isPresent()) {
            throw new AlreadyProjectMemberException();
        }

        ProjectMember member = new ProjectMember();
        member.setProject(project);
        member.setUser(invitee);
        member.setRole(request.role());
        projectMemberRepository.save(member);

        return MemberResponse.from(member);
    }

    @Transactional
    public MemberResponse updateRole(User currentUser, UUID projectId, UUID targetUserId, UpdateMemberRoleRequest request) {
        projectAccessService.findProjectOrThrow(projectId);
        ProjectMember myMembership = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(myMembership, ProjectRole.ADMIN);

        ProjectMember target = findMemberOrThrow(projectId, targetUserId);

        if (target.getRole() == ProjectRole.OWNER && request.role() != ProjectRole.OWNER) {
            requireAnotherOwnerExists(projectId);
        }

        target.setRole(request.role());
        projectMemberRepository.save(target);
        return MemberResponse.from(target);
    }

    @Transactional
    public void remove(User currentUser, UUID projectId, UUID targetUserId) {
        projectAccessService.findProjectOrThrow(projectId);
        ProjectMember myMembership = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(myMembership, ProjectRole.ADMIN);

        ProjectMember target = findMemberOrThrow(projectId, targetUserId);

        if (target.getRole() == ProjectRole.OWNER) {
            requireAnotherOwnerExists(projectId);
        }

        projectMemberRepository.delete(target);
    }

    private ProjectMember findMemberOrThrow(UUID projectId, UUID userId) {
        return projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
    }

    private void requireAnotherOwnerExists(UUID projectId) {
        if (projectMemberRepository.countByProjectIdAndRole(projectId, ProjectRole.OWNER) <= 1) {
            throw new CannotRemoveLastOwnerException();
        }
    }
}
