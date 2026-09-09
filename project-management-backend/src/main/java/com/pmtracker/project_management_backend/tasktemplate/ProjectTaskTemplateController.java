package com.pmtracker.project_management_backend.tasktemplate;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.tasktemplate.dto.TaskTemplateRequest;
import com.pmtracker.project_management_backend.tasktemplate.dto.TaskTemplateResponse;
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
@RequestMapping("/api/projects/{projectId}/task-templates")
@Tag(name = "Task templates", description = "Заготовки задач для повторяющихся типов работ: поля формы плюс чек-лист")
public class ProjectTaskTemplateController {

    private final TaskTemplateService taskTemplateService;

    public ProjectTaskTemplateController(TaskTemplateService taskTemplateService) {
        this.taskTemplateService = taskTemplateService;
    }

    @GetMapping
    @Operation(summary = "Шаблоны задач проекта",
            description = "Доступно любому участнику, включая VIEWER: список нужен форме заведения "
                    + "задачи. Пункты чек-листа здесь не приезжают, только их число (itemCount) — за "
                    + "самими пунктами ходят в GET /api/task-templates/{id}")
    public ResponseEntity<List<TaskTemplateResponse>> list(@AuthenticationPrincipal User currentUser,
                                                            @PathVariable UUID projectId) {
        return ResponseEntity.ok(taskTemplateService.list(currentUser, projectId));
    }

    @PostMapping
    @Operation(summary = "Завести шаблон задачи",
            description = "OWNER/ADMIN — как у спринтов, а не как у тэгов: шаблон не трогает уже "
                    + "заведённые задачи, он только заполняет пустую форму. Имя уникально в проекте "
                    + "(409 DUPLICATE_TASK_TEMPLATE_NAME)")
    public ResponseEntity<TaskTemplateResponse> create(@AuthenticationPrincipal User currentUser,
                                                        @PathVariable UUID projectId,
                                                        @Valid @RequestBody TaskTemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(taskTemplateService.create(currentUser, projectId, request));
    }
}
