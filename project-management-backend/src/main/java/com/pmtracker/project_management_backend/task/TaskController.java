package com.pmtracker.project_management_backend.task;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.task.dto.CreateTaskRequest;
import com.pmtracker.project_management_backend.task.dto.TaskResponse;
import com.pmtracker.project_management_backend.task.dto.UpdateTaskRequest;
import com.pmtracker.project_management_backend.task.dto.UpdateTaskStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tasks")
@Tag(name = "Tasks", description = "CRUD задач проекта; видимость и права зависят от роли текущего пользователя в project_members")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Детали задачи", description = "Доступно любому участнику проекта, включая VIEWER")
    public ResponseEntity<TaskResponse> get(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        return ResponseEntity.ok(taskService.getById(currentUser, id));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Редактировать задачу", description = "title/description/status/assigneeId; OWNER/ADMIN/MEMBER, не VIEWER")
    public ResponseEntity<TaskResponse> update(@AuthenticationPrincipal User currentUser,
                                                @PathVariable UUID id,
                                                @Valid @RequestBody UpdateTaskRequest request) {
        return ResponseEntity.ok(taskService.update(currentUser, id, request));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Сменить статус/позицию (канбан drag)",
            description = "Транзакционно пересчитывает position внутри затронутой колонки (тот же статус + тот же родитель); "
                    + "OWNER/ADMIN/MEMBER, не VIEWER. expectedStatus — статус задачи, известный клиенту на момент начала drag; "
                    + "если он не совпадает с текущим статусом в БД (задачу успел передвинуть кто-то ещё), возвращает 409 TASK_STATUS_CONFLICT")
    public ResponseEntity<TaskResponse> updateStatus(@AuthenticationPrincipal User currentUser,
                                                       @PathVariable UUID id,
                                                       @Valid @RequestBody UpdateTaskStatusRequest request) {
        return ResponseEntity.ok(taskService.updateStatus(currentUser, id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить задачу", description = "OWNER/ADMIN/MEMBER; каскадно удаляет подзадачи на уровне БД")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        taskService.delete(currentUser, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/subtasks")
    @Operation(summary = "Список подзадач", description = "Доступно любому участнику проекта, включая VIEWER")
    public ResponseEntity<List<TaskResponse>> listSubtasks(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        return ResponseEntity.ok(taskService.listSubtasks(currentUser, id));
    }

    @PostMapping("/{id}/subtasks")
    @Operation(summary = "Создать подзадачу", description = "project_id наследуется от родителя; OWNER/ADMIN/MEMBER")
    public ResponseEntity<TaskResponse> createSubtask(@AuthenticationPrincipal User currentUser,
                                                        @PathVariable UUID id,
                                                        @Valid @RequestBody CreateTaskRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(taskService.createSubtask(currentUser, id, request));
    }
}
