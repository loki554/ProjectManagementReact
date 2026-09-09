package com.pmtracker.project_management_backend.checklist;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.checklist.dto.ChecklistItemResponse;
import com.pmtracker.project_management_backend.checklist.dto.CreateChecklistItemRequest;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tasks/{taskId}/checklist")
@Tag(name = "Checklists", description = "Чек-лист задачи: шаги внутри одной задачи, в отличие от подзадач")
public class TaskChecklistController {

    private final ChecklistService checklistService;

    public TaskChecklistController(ChecklistService checklistService) {
        this.checklistService = checklistService;
    }

    @GetMapping
    @Operation(summary = "Чек-лист задачи",
            description = "Доступно любому участнику проекта, включая VIEWER. Порядок — тот, в котором "
                    + "пункты написаны. Счётчик «сделано из всего» есть и в самой задаче "
                    + "(TaskResponse.checklistDone/checklistTotal) — списку и доске хватает его")
    public ResponseEntity<List<ChecklistItemResponse>> list(@AuthenticationPrincipal User currentUser,
                                                            @PathVariable UUID taskId) {
        return ResponseEntity.ok(checklistService.list(currentUser, taskId));
    }

    @PostMapping
    @Operation(summary = "Добавить пункт чек-листа",
            description = "MEMBER и выше — то же право, что и на правку задачи. Пункт добавляется в конец")
    public ResponseEntity<ChecklistItemResponse> create(@AuthenticationPrincipal User currentUser,
                                                         @PathVariable UUID taskId,
                                                         @Valid @RequestBody CreateChecklistItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(checklistService.create(currentUser, taskId, request));
    }
}
