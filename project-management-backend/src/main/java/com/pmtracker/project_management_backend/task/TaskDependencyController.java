package com.pmtracker.project_management_backend.task;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.task.dto.AddTaskDependencyRequest;
import com.pmtracker.project_management_backend.task.dto.TaskDependenciesResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/tasks/{id}/dependencies")
@Tag(name = "Task dependencies", description = "Горизонтальные связи между задачами проекта: «блокирует» / «заблокирована»")
public class TaskDependencyController {

    private final TaskDependencyService taskDependencyService;

    public TaskDependencyController(TaskDependencyService taskDependencyService) {
        this.taskDependencyService = taskDependencyService;
    }

    @GetMapping
    @Operation(summary = "Зависимости задачи",
            description = "Обе стороны одним ответом: blockedBy — кто мешает закрыть эту задачу, "
                    + "blocks — кому мешает она. Задачи, уехавшие в корзину, из обоих списков "
                    + "выпадают и возвращаются вместе с восстановлением. Доступно любому участнику "
                    + "проекта, включая VIEWER")
    public ResponseEntity<TaskDependenciesResponse> list(@AuthenticationPrincipal User currentUser,
                                                          @PathVariable UUID id) {
        return ResponseEntity.ok(taskDependencyService.list(currentUser, id));
    }

    @PostMapping
    @Operation(summary = "Добавить блокер",
            description = "«Задачу из URL блокирует blockerTaskId». Обе задачи должны быть из одного "
                    + "проекта (иначе 400 DEPENDENCY_PROJECT_MISMATCH); задача не может блокировать "
                    + "сама себя (400 SELF_DEPENDENCY); повтор той же связи — 409 DUPLICATE_DEPENDENCY; "
                    + "связь, замыкающая кольцо блокеров, — 400 DEPENDENCY_CYCLE. OWNER/ADMIN/MEMBER, "
                    + "не VIEWER. В ответе — обе стороны зависимостей целиком")
    public ResponseEntity<TaskDependenciesResponse> add(@AuthenticationPrincipal User currentUser,
                                                         @PathVariable UUID id,
                                                         @Valid @RequestBody AddTaskDependencyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(taskDependencyService.add(currentUser, id, request.blockerTaskId()));
    }

    @DeleteMapping("/{blockerTaskId}")
    @Operation(summary = "Снять блокер",
            description = "Удаляется только связь, обе задачи остаются на месте. Несуществующая связь — "
                    + "404 DEPENDENCY_NOT_FOUND. OWNER/ADMIN/MEMBER, не VIEWER. В ответе — обе стороны "
                    + "зависимостей после удаления")
    public ResponseEntity<TaskDependenciesResponse> remove(@AuthenticationPrincipal User currentUser,
                                                            @PathVariable UUID id,
                                                            @PathVariable UUID blockerTaskId) {
        return ResponseEntity.ok(taskDependencyService.remove(currentUser, id, blockerTaskId));
    }
}
