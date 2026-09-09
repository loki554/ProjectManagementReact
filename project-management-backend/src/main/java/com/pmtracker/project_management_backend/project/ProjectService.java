package com.pmtracker.project_management_backend.project;

import com.pmtracker.project_management_backend.activity.ActivityService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.exception.ConcurrentModificationConflictException;
import com.pmtracker.project_management_backend.common.exception.InvalidFileException;
import com.pmtracker.project_management_backend.common.exception.InvalidProjectNameException;
import com.pmtracker.project_management_backend.common.exception.ProjectNameAlreadyExistsException;
import com.pmtracker.project_management_backend.common.exception.ProjectNotFoundException;
import com.pmtracker.project_management_backend.common.exception.ResourceNotFoundException;
import com.pmtracker.project_management_backend.project.dto.CreateProjectRequest;
import com.pmtracker.project_management_backend.project.dto.ProjectResponse;
import com.pmtracker.project_management_backend.project.dto.UpdateProjectRequest;
import com.pmtracker.project_management_backend.storage.FileStorageService;
import com.pmtracker.project_management_backend.storage.FileTypeValidator;
import com.pmtracker.project_management_backend.storage.ImageSanitizer;
import com.pmtracker.project_management_backend.storage.SanitizedImage;
import com.pmtracker.project_management_backend.storage.StoredFile;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
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

    // Превью показывается карточкой, а не в полный экран, но крупнее аватарки — отсюда 1024
    // против 512 у UserService. Всё, что больше, ужимается при перекодировании (ImageSanitizer).
    private static final int MAX_PREVIEW_IMAGE_DIMENSION = 1024;

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectMembershipCache membershipCache;
    private final ProjectAccessService projectAccessService;
    private final FileStorageService fileStorageService;
    private final FileTypeValidator fileTypeValidator;
    private final ImageSanitizer imageSanitizer;
    private final ActivityService activityService;

    public ProjectService(ProjectRepository projectRepository,
                           ProjectMemberRepository projectMemberRepository,
                           ProjectMembershipCache membershipCache,
                           ProjectAccessService projectAccessService,
                           FileStorageService fileStorageService,
                           FileTypeValidator fileTypeValidator,
                           ImageSanitizer imageSanitizer,
                           ActivityService activityService) {
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.membershipCache = membershipCache;
        this.projectAccessService = projectAccessService;
        this.fileStorageService = fileStorageService;
        this.fileTypeValidator = fileTypeValidator;
        this.imageSanitizer = imageSanitizer;
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
        // Создатель до этого момента участником не был, и отрицательный ответ на него мог
        // осесть в кэше (3.10) — например, если он только что заходил по этой ссылке.
        membershipCache.invalidate(project.getId(), currentUser.getId());

        return ProjectResponse.from(project, ProjectRole.OWNER);
    }

    /**
     * Проекты пользователя — по умолчанию только действующие (4.14).
     *
     * <p>Отбор в памяти, а не в запросе: список проектов одного человека — это единицы или
     * десятки строк, которые уже загружены вместе с join'ом на участие, и второй запрос с
     * условием сэкономил бы здесь одно сравнение булева поля. Когда проектов у человека
     * станут сотни, менять придётся не это условие, а сам список — он и сегодня приезжает
     * без пагинации (см. 3.8.1).
     *
     * <p>Архив отдаётся тем же эндпоинтом с {@code ?archived=true}, а не собственным: это
     * тот же список тех же проектов, и разделять его на два — значит однажды показать в
     * архиве другой набор полей, чем в списке.
     */
    @Transactional(readOnly = true)
    public List<ProjectResponse> listForUser(User currentUser, boolean archived) {
        return projectMemberRepository.findByUserIdWithProject(currentUser.getId()).stream()
                .filter(membership -> membership.getProject().isArchived() == archived)
                .map(membership -> ProjectResponse.from(membership.getProject(), membership.getRole()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse getById(User currentUser, UUID projectId) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        ProjectRole role = projectAccessService.requireMembership(projectId, currentUser);
        return ProjectResponse.from(project, role);
    }

    // idOrSlug: обычно человекочитаемый slug (см. §URL), но старые ссылки/закладки, выданные до
    // введения slug'ов, содержат сырой UUID — поддерживаем оба формата в одном эндпоинте, чтобы
    // такие ссылки не превратились в 404.
    @Transactional(readOnly = true)
    public ProjectResponse getBySlugOrId(User currentUser, String idOrSlug) {
        Project project = resolveBySlugOrId(idOrSlug);
        ProjectRole role = projectAccessService.requireMembership(project.getId(), currentUser);
        return ProjectResponse.from(project, role);
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
        ProjectRole role = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireWriteRole(project, role, ProjectRole.OWNER);
        // Версия из формы (3.4) — до применения правок, чтобы конфликт не оставил
        // за собой запись в ленте активности.
        if (request.version() == null || request.version() != project.getVersion()) {
            throw new ConcurrentModificationConflictException();
        }

        // Список кодов изменённых полей — фронтенд переводит их сам; пустой дифф
        // (сабмит без правок) событием не считается.
        List<String> changedFields = new ArrayList<>();
        if (!Objects.equals(project.getName(), request.name())) {
            changedFields.add("name");
        }
        if (!Objects.equals(project.getDescription(), request.description())) {
            changedFields.add("description");
        }

        project.setName(request.name());
        project.setDescription(request.description());
        // saveAndFlush — см. WikiService.update: ответ должен нести уже увеличенную версию.
        projectRepository.saveAndFlush(project);

        if (!changedFields.isEmpty()) {
            activityService.record(project, currentUser, "project_updated", null,
                    Map.of("changedFields", changedFields));
        }

        return ProjectResponse.from(project, role);
    }

    /**
     * Убрать проект в архив (4.14).
     *
     * <p>Отдельное действие, а не галочка в форме настроек, какой архивация была до сих
     * пор. Причин две. Первая: у архивации есть последствия — проект уходит из списка и
     * перестаёт принимать правки, — и такое не подписывают заодно с исправлением опечатки
     * в названии. Вторая: галочка в форме ехала вместе с версией (3.4), то есть
     * «архивировать» отказывало с 409, если кто-то в этот момент переименовал проект, — при
     * том что архивации совершенно всё равно, как проект называется.
     *
     * <p>Идемпотентно: архивировать уже архивный проект — не ошибка, а просьба, которая уже
     * выполнена (две вкладки, повторный клик). Дату при этом не переписываем — «когда
     * закончили» не должно сдвигаться от повторного нажатия.
     */
    @Transactional
    public ProjectResponse archive(User currentUser, UUID projectId) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        ProjectRole role = projectAccessService.requireMembership(projectId, currentUser);
        // Архивация — настройка проекта, то есть OWNER (см. таблицу ролей §5). Не
        // requireWriteRole: архивировать архивный проект должно быть можно, иначе повторный
        // клик отвечал бы ошибкой на уже достигнутое состояние.
        projectAccessService.requireOwnerIgnoringArchive(role);

        if (!project.isArchived()) {
            project.setArchived(true);
            project.setArchivedAt(Instant.now());
            projectRepository.saveAndFlush(project);
            activityService.record(project, currentUser, "project_archived", null, Map.of());
        }
        return ProjectResponse.from(project, role);
    }

    /**
     * Вернуть проект из архива. Симметрично архивации и по той же причине идемпотентно.
     * Проверка роли — {@code requireOwnerIgnoringArchive}: это единственный способ выйти из
     * архива, и запрещать его из-за архива значило бы сделать архив состоянием без выхода.
     */
    @Transactional
    public ProjectResponse unarchive(User currentUser, UUID projectId) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        ProjectRole role = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireOwnerIgnoringArchive(role);

        if (project.isArchived()) {
            project.setArchived(false);
            // Дата снимается вместе с флагом: «в архиве с 3 марта» — это про текущее
            // состояние, а не про историю. Историю архиваций, если она однажды
            // понадобится, несёт лента активности, где остались оба события.
            project.setArchivedAt(null);
            projectRepository.saveAndFlush(project);
            activityService.record(project, currentUser, "project_unarchived", null, Map.of());
        }
        return ProjectResponse.from(project, role);
    }

    /**
     * Удаление проекта навсегда. Единственная правка, разрешённая и в архиве (4.14):
     * архив — это «закончено», а не «нельзя тронуть», и выкинуть закрытый год назад проект
     * владелец должен уметь, не возвращая его перед этим из архива.
     */
    @Transactional
    public void delete(User currentUser, UUID projectId) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        ProjectRole role = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireOwnerIgnoringArchive(role);
        String previewImagePath = project.getPreviewImagePath();
        projectRepository.deleteById(projectId);
        membershipCache.invalidateProject(projectId);
        if (previewImagePath != null) {
            fileStorageService.delete(previewImagePath);
        }
    }

    // Права — как у update() (только OWNER, см. таблицу ролей §5 "Настройки проекта"):
    // загрузка превью-картинки — часть настроек проекта, не отдельное разрешение.
    @Transactional
    public ProjectResponse uploadPreviewImage(User currentUser, UUID projectId, MultipartFile file) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        ProjectRole role = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireWriteRole(project, role, ProjectRole.OWNER);

        if (file.isEmpty()) {
            throw new InvalidFileException("No file selected");
        }
        if (file.getSize() > MAX_PREVIEW_IMAGE_SIZE_BYTES) {
            throw new InvalidFileException("File is too large (max 5MB)");
        }
        if (!ALLOWED_PREVIEW_IMAGE_CONTENT_TYPES.contains(file.getContentType())) {
            throw new InvalidFileException("Allowed image formats: PNG, JPEG, WEBP, GIF");
        }
        fileTypeValidator.requireContentMatchesDeclaredType(file);

        // Как и у аватарки (UserService.uploadAvatar) — на диск ложатся перекодированные пиксели,
        // а не присланные байты, см. 1.12 IMPROVEMENTS.md.
        SanitizedImage image = imageSanitizer.sanitize(file, MAX_PREVIEW_IMAGE_DIMENSION);

        String previousPreviewImagePath = project.getPreviewImagePath();

        StoredFile stored;
        try {
            stored = fileStorageService.store(image.content(), image.extension(), "projects/" + projectId);
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

        return ProjectResponse.from(project, role);
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
