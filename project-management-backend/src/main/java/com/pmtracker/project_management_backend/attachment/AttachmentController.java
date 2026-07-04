package com.pmtracker.project_management_backend.attachment;

import com.pmtracker.project_management_backend.auth.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/attachments")
@Tag(name = "Attachments", description = "Вложения к задаче")
public class AttachmentController {

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    // Скачивание требует авторизации и членства в проекте задачи (не static hosting,
    // см. §5 IMPLEMENTATION_PLAN.md) — путь к файлу на диске не угадываемый и не публичный.
    @GetMapping("/{id}/download")
    @Operation(summary = "Скачать вложение", description = "Доступно любому участнику проекта, включая VIEWER")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        AttachmentDownload download = attachmentService.download(currentUser, id);
        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename(download.originalFilename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .body(download.resource());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить вложение", description = "Загрузивший пользователь, либо OWNER/ADMIN проекта; иначе 403 NOT_ATTACHMENT_OWNER")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        attachmentService.delete(currentUser, id);
        return ResponseEntity.noContent().build();
    }
}
