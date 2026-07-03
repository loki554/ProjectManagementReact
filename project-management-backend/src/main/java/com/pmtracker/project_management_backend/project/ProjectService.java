package com.pmtracker.project_management_backend.project;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.exception.InsufficientProjectRoleException;
import com.pmtracker.project_management_backend.common.exception.NotProjectMemberException;
import com.pmtracker.project_management_backend.common.exception.ProjectNotFoundException;
import com.pmtracker.project_management_backend.project.dto.CreateProjectRequest;
import com.pmtracker.project_management_backend.project.dto.ProjectResponse;
import com.pmtracker.project_management_backend.project.dto.UpdateProjectRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;

    public ProjectService(ProjectRepository projectRepository, ProjectMemberRepository projectMemberRepository) {
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
    }

    @Transactional
    public ProjectResponse create(User currentUser, CreateProjectRequest request) {
        Project project = new Project();
        project.setName(request.name());
        project.setDescription(request.description());
        project.setCreatedBy(currentUser);
        projectRepository.save(project);

        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(currentUser);
        membership.setRole(ProjectRole.OWNER);
        projectMemberRepository.save(membership);

        return ProjectResponse.from(project, ProjectRole.OWNER);
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> listForUser(User currentUser) {
        return projectMemberRepository.findByUserIdOrderByProject_CreatedAtDesc(currentUser.getId()).stream()
                .map(membership -> ProjectResponse.from(membership.getProject(), membership.getRole()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse getById(User currentUser, UUID projectId) {
        Project project = findProjectOrThrow(projectId);
        ProjectMember membership = requireMembership(project, currentUser);
        return ProjectResponse.from(project, membership.getRole());
    }

    @Transactional
    public ProjectResponse update(User currentUser, UUID projectId, UpdateProjectRequest request) {
        Project project = findProjectOrThrow(projectId);
        ProjectMember membership = requireMembership(project, currentUser);
        requireRole(membership, ProjectRole.OWNER);

        project.setName(request.name());
        project.setDescription(request.description());
        project.setArchived(request.archived());
        projectRepository.save(project);

        return ProjectResponse.from(project, membership.getRole());
    }

    @Transactional
    public void delete(User currentUser, UUID projectId) {
        Project project = findProjectOrThrow(projectId);
        ProjectMember membership = requireMembership(project, currentUser);
        requireRole(membership, ProjectRole.OWNER);
        projectRepository.deleteById(projectId);
    }

    private Project findProjectOrThrow(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(ProjectNotFoundException::new);
    }

    private ProjectMember requireMembership(Project project, User user) {
        return projectMemberRepository.findByProjectIdAndUserId(project.getId(), user.getId())
                .orElseThrow(NotProjectMemberException::new);
    }

    private void requireRole(ProjectMember membership, ProjectRole required) {
        if (!membership.getRole().isAtLeast(required)) {
            throw new InsufficientProjectRoleException();
        }
    }
}
