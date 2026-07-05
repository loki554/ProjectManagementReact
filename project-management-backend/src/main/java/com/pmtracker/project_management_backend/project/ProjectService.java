package com.pmtracker.project_management_backend.project;

import com.pmtracker.project_management_backend.activity.ActivityService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.exception.InvalidFileException;
import com.pmtracker.project_management_backend.common.exception.InvalidProjectNameException;
import com.pmtracker.project_management_backend.common.exception.ProjectNameAlreadyExistsException;
import com.pmtracker.project_management_backend.common.exception.ProjectNotFoundException;
import com.pmtracker.project_management_backend.common.exception.ResourceNotFoundException;
import com.pmtracker.project_management_backend.project.dto.CreateProjectRequest;
import com.pmtracker.project_management_backend.project.dto.ProjectResponse;
import com.pmtracker.project_management_backend.project.dto.UpdateProjectRequest;
import com.pmtracker.project_management_backend.storage.FileStorageService;
import com.pmtracker.project_management_backend.storage.StoredFile;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ProjectService {

    private static final Set<String> ALLOWED_PREVIEW_IMAGE_CONTENT_TYPES =
            Set.of("image/png", "image/jpeg", "image/webp", "image/gif");

    // "не супер большая" (пользовательский запрос) — тот же лимит, что и у аватарки
    // пользователя (UserService.MAX_AVATAR_SIZE_BYTES), сопоставимый по смыслу артефакт.
    private static final long MAX_PREVIEW_IMAGE_SIZE_BYTES = 5L * 1024 * 1024;

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectAccessService projectAccessService;
    private final FileStorageService fileStorageService;
    private final ActivityService activityService;

    public ProjectService(ProjectRepository projectRepository,
                           ProjectMemberRepository projectMemberRepository,
                           ProjectAccessService projectAccessService,
                           FileStorageService fileStorageService,
                           ActivityService activityService) {
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.projectAccessService = projectAccessService;
        this.fileStorageService = fileStorageService;
        this.activityService = activityService;
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

        // Список кодов изменённых полей — фронтенд переводит их сам; пустой дифф
        // (сабмит без правок) событием не считается.
        List<String> changedFields = new ArrayList<>();
        if (!Objects.equals(project.getName(), request.name())) {
            changedFields.add("name");
        }
        if (!Objects.equals(project.getDescription(), request.description())) {
            changedFields.add("description");
        }
        if (project.isArchived() != request.archived()) {
            changedFields.add("archived");
        }

        project.setName(request.name());
        project.setDescription(request.description());
        project.setArchived(request.archived());
        projectRepository.save(project);

        if (!changedFields.isEmpty()) {
            activityService.record(project, currentUser, "project_updated", null,
                    Map.of("changedFields", changedFields));
        }

        return ProjectResponse.from(project, membership.getRole());
    }

    @Transactional
    public void delete(User currentUser, UUID projectId) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        ProjectMember membership = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(membership, ProjectRole.OWNER);
        String previewImagePath = project.getPreviewImagePath();
        projectRepository.deleteById(projectId);
        if (previewImagePath != null) {
            fileStorageService.delete(previewImagePath);
        }
    }

    // Права — как у update() (только OWNER, см. таблицу ролей §5 "Настройки проекта"):
    // загрузка превью-картинки — часть настроек проекта, не отдельное разрешение.
    @Transactional
    public ProjectResponse uploadPreviewImage(User currentUser, UUID projectId, MultipartFile file) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        ProjectMember membership = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(membership, ProjectRole.OWNER);

        if (file.isEmpty()) {
            throw new InvalidFileException("No file selected");
        }
        if (file.getSize() > MAX_PREVIEW_IMAGE_SIZE_BYTES) {
            throw new InvalidFileException("File is too large (max 5MB)");
        }
        if (!ALLOWED_PREVIEW_IMAGE_CONTENT_TYPES.contains(file.getContentType())) {
            throw new InvalidFileException("Allowed image formats: PNG, JPEG, WEBP, GIF");
        }

        String previousPreviewImagePath = project.getPreviewImagePath();

        StoredFile stored;
        try {
            stored = fileStorageService.store(file, "projects/" + projectId);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save preview image file", e);
        }

        project.setPreviewImagePath(stored.relativePath());
        projectRepository.save(project);

        // Как и у аватарки (UserService.uploadAvatar) — удаляем старый файл только после того,
        // как новый успешно сохранён и БД обновлена.
        if (previousPreviewImagePath != null) {
            fileStorageService.delete(previousPreviewImagePath);
        }

        return ProjectResponse.from(project, membership.getRole());
    }

    // Просмотр открыт всем ролям проекта, включая VIEWER — как и остальные детали проекта
    // (getById/getBySlugOrId).
    @Transactional(readOnly = true)
    public Resource getPreviewImageResource(User currentUser, UUID projectId) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);
        if (project.getPreviewImagePath() == null) {
            throw new ResourceNotFoundException("Project has no preview image");
        }
        try {
            return fileStorageService.load(project.getPreviewImagePath());
        } catch (NoSuchElementException e) {
            throw new ResourceNotFoundException("Preview image file not found");
        }
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
