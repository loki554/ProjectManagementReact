package com.pmtracker.project_management_backend.attachment;

import com.pmtracker.project_management_backend.attachment.dto.AttachmentResponse;
import com.pmtracker.project_management_backend.auth.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tasks/{id}/attachments")
@Tag(name = "Attachments", description = "Вложения к задаче")
public class TaskAttachmentController {

    private final AttachmentService attachmentService;

    public TaskAttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Загрузить вложение", description = "OWNER/ADMIN/MEMBER, не VIEWER; whitelist MIME-типов + лимит размера")
    public ResponseEntity<AttachmentResponse> upload(@AuthenticationPrincipal User currentUser,
                                                       @PathVariable UUID id,
                                                       @RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(attachmentService.upload(currentUser, id, file));
    }

    @GetMapping
    @Operation(summary = "Список вложений задачи", description = "Доступно любому участнику проекта, включая VIEWER")
    public ResponseEntity<List<AttachmentResponse>> list(@AuthenticationPrincipal User currentUser,
                                                           @PathVariable UUID id) {
        return ResponseEntity.ok(attachmentService.list(currentUser, id));
    }
}
