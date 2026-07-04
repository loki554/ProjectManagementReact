package com.pmtracker.project_management_backend.project;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.exception.InvalidProjectNameException;
import com.pmtracker.project_management_backend.common.exception.ProjectNameAlreadyExistsException;
import com.pmtracker.project_management_backend.common.exception.ProjectNotFoundException;
import com.pmtracker.project_management_backend.project.dto.CreateProjectRequest;
import com.pmtracker.project_management_backend.project.dto.ProjectResponse;
import com.pmtracker.project_management_backend.project.dto.UpdateProjectRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
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
        String slug = slugify(request.name());
        if (slug.isEmpty()) {
            throw new InvalidProjectNameException();
        }
        if (projectRepository.existsBySlug(slug)) {
            throw new ProjectNameAlreadyExistsException();
        }

        Project project = new Project();
        project.setName(request.name());
        project.setSlug(slug);
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

    // idOrSlug: обычно человекочитаемый slug (см. §URL), но старые ссылки/закладки, выданные до
    // введения slug'ов, содержат сырой UUID — поддерживаем оба формата в одном эндпоинте, чтобы
    // такие ссылки не превратились в 404.
    @Transactional(readOnly = true)
    public ProjectResponse getBySlugOrId(User currentUser, String idOrSlug) {
        Project project = resolveBySlugOrId(idOrSlug);
        ProjectMember membership = projectAccessService.requireMembership(project.getId(), currentUser);
        return ProjectResponse.from(project, membership.getRole());
    }

    private Project resolveBySlugOrId(String idOrSlug) {
        try {
            return projectAccessService.findProjectOrThrow(UUID.fromString(idOrSlug));
        } catch (IllegalArgumentException notAUuid) {
            return projectRepository.findBySlug(idOrSlug).orElseThrow(ProjectNotFoundException::new);
        }
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

    // lowercase + любая последовательность не-alphanumeric символов схлопывается в один "-" +
    // обрезка дефисов по краям (см. также бэкофилл в V9__project_slug.sql — та же логика в SQL,
    // должна оставаться синхронизированной с этим методом). @Pattern на CreateProjectRequest.name
    // уже гарантирует ASCII-only, так что здесь не нужно думать о не-латинских символах.
    private String slugify(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return slug.replaceAll("^-+|-+$", "");
    }
}
