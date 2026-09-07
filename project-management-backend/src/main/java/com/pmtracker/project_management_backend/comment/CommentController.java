package com.pmtracker.project_management_backend.comment;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.comment.dto.CommentResponse;
import com.pmtracker.project_management_backend.comment.dto.UpdateCommentRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/comments")
@Tag(name = "Comments", description = "Комментарии к задаче")
public class CommentController {

    private final TaskCommentService taskCommentService;

    public CommentController(TaskCommentService taskCommentService) {
        this.taskCommentService = taskCommentService;
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Изменить комментарий",
            description = "Только автор комментария — модераторы чужой текст не правят, "
                    + "иначе 403 NOT_COMMENT_AUTHOR; plain text до 2000 символов")
    public ResponseEntity<CommentResponse> update(@AuthenticationPrincipal User currentUser,
                                                    @PathVariable UUID id,
                                                    @Valid @RequestBody UpdateCommentRequest request) {
        return ResponseEntity.ok(taskCommentService.update(currentUser, id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить комментарий", description = "Автор комментария, либо OWNER/ADMIN проекта; иначе 403 NOT_COMMENT_OWNER")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        taskCommentService.delete(currentUser, id);
        return ResponseEntity.noContent().build();
    }
}
