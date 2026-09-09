package com.pmtracker.project_management_backend.tasktemplate;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.tasktemplate.dto.TaskTemplateRequest;
import com.pmtracker.project_management_backend.tasktemplate.dto.TaskTemplateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/task-templates/{id}")
@Tag(name = "Task templates", description = "Заготовки задач для повторяющихся типов работ: поля формы плюс чек-лист")
public class TaskTemplateController {

    private final TaskTemplateService taskTemplateService;

    public TaskTemplateController(TaskTemplateService taskTemplateService) {
        this.taskTemplateService = taskTemplateService;
    }

    @GetMapping
    @Operation(summary = "Шаблон целиком, вместе с пунктами чек-листа",
            description = "Доступно любому участнику, включая VIEWER. Это то, что читает форма "
                    + "заведения задачи, когда в ней выбрали шаблон")
    public ResponseEntity<TaskTemplateResponse> get(@AuthenticationPrincipal User currentUser,
                                                     @PathVariable UUID id) {
        return ResponseEntity.ok(taskTemplateService.get(currentUser, id));
    }

    // PUT, а не PATCH, и вместе с чек-листом: шаблон правят формой целиком (см.
    // TaskTemplateRequest).
    @PutMapping
    @Operation(summary = "Переписать шаблон",
            description = "OWNER/ADMIN. Пункты чек-листа заменяются целиком тем списком, что пришёл. "
                    + "Уже заведённые по шаблону задачи не меняются: шаблон копируется в момент "
                    + "создания задачи и источником правды для неё не остаётся")
    public ResponseEntity<TaskTemplateResponse> update(@AuthenticationPrincipal User currentUser,
                                                        @PathVariable UUID id,
                                                        @Valid @RequestBody TaskTemplateRequest request) {
        return ResponseEntity.ok(taskTemplateService.update(currentUser, id, request));
    }

    @DeleteMapping
    @Operation(summary = "Удалить шаблон",
            description = "OWNER/ADMIN. Задачи, заведённые по шаблону, не трогаются")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        taskTemplateService.delete(currentUser, id);
        return ResponseEntity.noContent().build();
    }
}
