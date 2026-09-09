package com.pmtracker.project_management_backend.checklist;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.checklist.dto.ChecklistItemResponse;
import com.pmtracker.project_management_backend.checklist.dto.UpdateChecklistItemRequest;
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
@RequestMapping("/api/checklist-items/{id}")
@Tag(name = "Checklists", description = "Чек-лист задачи: шаги внутри одной задачи, в отличие от подзадач")
public class ChecklistItemController {

    private final ChecklistService checklistService;

    public ChecklistItemController(ChecklistService checklistService) {
        this.checklistService = checklistService;
    }

    // PATCH, а не PUT: галочку и текст правят разными жестами, и клик по галочке не должен
    // присылать обратно текст (см. UpdateChecklistItemRequest).
    @PatchMapping
    @Operation(summary = "Отметить пункт или переписать его текст",
            description = "MEMBER и выше. Любое из полей можно не присылать — оно останется как есть")
    public ResponseEntity<ChecklistItemResponse> update(@AuthenticationPrincipal User currentUser,
                                                         @PathVariable UUID id,
                                                         @Valid @RequestBody UpdateChecklistItemRequest request) {
        return ResponseEntity.ok(checklistService.update(currentUser, id, request));
    }

    @DeleteMapping
    @Operation(summary = "Удалить пункт чек-листа", description = "MEMBER и выше")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        checklistService.delete(currentUser, id);
        return ResponseEntity.noContent().build();
    }
}
