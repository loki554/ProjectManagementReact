package com.pmtracker.project_management_backend.comment;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.comment.dto.CommentResponse;
import com.pmtracker.project_management_backend.comment.dto.CreateCommentRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tasks/{id}/comments")
@Tag(name = "Comments", description = "Комментарии к задаче")
public class TaskCommentController {

    private final TaskCommentService taskCommentService;

    public TaskCommentController(TaskCommentService taskCommentService) {
        this.taskCommentService = taskCommentService;
    }

    @PostMapping
    @Operation(summary = "Добавить комментарий", description = "OWNER/ADMIN/MEMBER, не VIEWER; plain text до 2000 символов")
    public ResponseEntity<CommentResponse> create(@AuthenticationPrincipal User currentUser,
                                                    @PathVariable UUID id,
                                                    @Valid @RequestBody CreateCommentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(taskCommentService.create(currentUser, id, request));
    }

    @GetMapping
    @Operation(summary = "Список комментариев задачи",
            description = "Доступно любому участнику проекта, включая VIEWER; sort=newest (default) | oldest")
    public ResponseEntity<List<CommentResponse>> list(@AuthenticationPrincipal User currentUser,
                                                        @PathVariable UUID id,
                                                        @RequestParam(defaultValue = "newest") String sort) {
        return ResponseEntity.ok(taskCommentService.list(currentUser, id, sort));
    }
}
