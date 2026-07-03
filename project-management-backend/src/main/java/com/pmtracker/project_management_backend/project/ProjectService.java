package com.pmtracker.project_management_backend.project;

import com.pmtracker.project_management_backend.auth.User;
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
    private final ProjectAccessService projectAccessService;

    public ProjectService(ProjectRepository projectRepository,
                           ProjectMemberRepository projectMemberRepository,
                           ProjectAccessService projectAccessService) {
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.projectAccessService = projectAccessService;
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
        Project project = projectAccessService.findProjectOrThrow(projectId);
        ProjectMember membership = projectAccessService.requireMembership(projectId, currentUser);
        return ProjectResponse.from(project, membership.getRole());
    }

    @Transactional
    public ProjectResponse update(User currentUser, UUID projectId, UpdateProjectRequest request) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        ProjectMember membership = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(membership, ProjectRole.OWNER);

        project.setName(request.name());
        project.setDescription(request.description());
        project.setArchived(request.archived());
        projectRepository.save(project);

        return ProjectResponse.from(project, membership.getRole());
    }

    @Transactional
    public void delete(User currentUser, UUID projectId) {
        projectAccessService.findProjectOrThrow(projectId);
        ProjectMember membership = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(membership, ProjectRole.OWNER);
        projectRepository.deleteById(projectId);
    }
}
