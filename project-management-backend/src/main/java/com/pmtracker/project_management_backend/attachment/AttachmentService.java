package com.pmtracker.project_management_backend.attachment;

import com.pmtracker.project_management_backend.attachment.dto.AttachmentResponse;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.exception.AttachmentNotFoundException;
import com.pmtracker.project_management_backend.common.exception.InvalidFileException;
import com.pmtracker.project_management_backend.common.exception.NotAttachmentOwnerException;
import com.pmtracker.project_management_backend.common.exception.ResourceNotFoundException;
import com.pmtracker.project_management_backend.common.exception.TaskNotFoundException;
import com.pmtracker.project_management_backend.project.ProjectAccessService;
import com.pmtracker.project_management_backend.project.ProjectMember;
import com.pmtracker.project_management_backend.project.ProjectRole;
import com.pmtracker.project_management_backend.storage.FileStorageService;
import com.pmtracker.project_management_backend.storage.StoredFile;
import com.pmtracker.project_management_backend.task.Task;
import com.pmtracker.project_management_backend.task.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
public class AttachmentService {

    // images, pdf, doc/docx/xls/xlsx, txt, zip — см. §5 IMPLEMENTATION_PLAN.md
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/gif",
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/plain",
            "application/zip", "application/x-zip-compressed"
    );

    // Верхняя граница на уровне сервиса — defense-in-depth к глобальному
    // spring.servlet.multipart.max-file-size (20MB, см. application.properties);
    // держим оба значения синхронными.
    private static final long MAX_SIZE_BYTES = 20L * 1024 * 1024;

    private final AttachmentRepository attachmentRepository;
    private final TaskRepository taskRepository;
    private final ProjectAccessService projectAccessService;
    private final FileStorageService fileStorageService;

    public AttachmentService(AttachmentRepository attachmentRepository,
                              TaskRepository taskRepository,
                              ProjectAccessService projectAccessService,
                              FileStorageService fileStorageService) {
        this.attachmentRepository = attachmentRepository;
        this.taskRepository = taskRepository;
        this.projectAccessService = projectAccessService;
        this.fileStorageService = fileStorageService;
    }

    @Transactional
    public AttachmentResponse upload(User currentUser, UUID taskId, MultipartFile file) {
        Task task = findTaskOrThrow(taskId);
        ProjectMember membership = projectAccessService.requireMembership(task.getProject().getId(), currentUser);
        projectAccessService.requireRole(membership, ProjectRole.MEMBER);

        if (file.isEmpty()) {
            throw new InvalidFileException("No file selected");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new InvalidFileException("File is too large (max 20MB)");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
            throw new InvalidFileException("Unsupported file type: " + file.getContentType());
        }

        StoredFile stored;
        try {
            stored = fileStorageService.store(file, "tasks/" + taskId);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save attachment file", e);
        }

        Attachment attachment = new Attachment();
        attachment.setTask(task);
        attachment.setUploadedBy(currentUser);
        attachment.setOriginalFilename(file.getOriginalFilename());
        attachment.setStoredPath(stored.relativePath());
        attachment.setContentType(file.getContentType());
        attachment.setSizeBytes(stored.sizeBytes());
        attachmentRepository.save(attachment);

        return AttachmentResponse.from(attachment);
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(User currentUser, UUID taskId) {
        Task task = findTaskOrThrow(taskId);
        projectAccessService.requireMembership(task.getProject().getId(), currentUser);

        return attachmentRepository.findByTaskIdOrderByCreatedAtDesc(taskId).stream()
                .map(AttachmentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AttachmentDownload download(User currentUser, UUID attachmentId) {
        Attachment attachment = findAttachmentOrThrow(attachmentId);
        projectAccessService.requireMembership(attachment.getTask().getProject().getId(), currentUser);

        try {
            var resource = fileStorageService.load(attachment.getStoredPath());
            return new AttachmentDownload(resource, attachment.getOriginalFilename(), attachment.getContentType());
        } catch (NoSuchElementException e) {
            // запись в БД есть, а файла на диске уже нет — не должно происходить в норме,
            // но лучше вернуть понятный 404, чем уронить запрос в 500 (см. UserService.getAvatarResource)
            throw new ResourceNotFoundException("Attachment file not found");
        }
    }

    @Transactional
    public void delete(User currentUser, UUID attachmentId) {
        Attachment attachment = findAttachmentOrThrow(attachmentId);
        UUID projectId = attachment.getTask().getProject().getId();
        ProjectMember membership = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(membership, ProjectRole.MEMBER);

        boolean isUploader = attachment.getUploadedBy().getId().equals(currentUser.getId());
        boolean isModerator = membership.getRole().isAtLeast(ProjectRole.ADMIN);
        if (!isUploader && !isModerator) {
            throw new NotAttachmentOwnerException();
        }

        attachmentRepository.delete(attachment);
        fileStorageService.delete(attachment.getStoredPath());
    }

    private Task findTaskOrThrow(UUID taskId) {
        return taskRepository.findById(taskId).orElseThrow(TaskNotFoundException::new);
    }

    private Attachment findAttachmentOrThrow(UUID attachmentId) {
        return attachmentRepository.findById(attachmentId).orElseThrow(AttachmentNotFoundException::new);
    }
}
