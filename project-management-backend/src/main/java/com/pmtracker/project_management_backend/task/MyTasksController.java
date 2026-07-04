package com.pmtracker.project_management_backend.task;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.dto.PageResponse;
import com.pmtracker.project_management_backend.task.dto.MyActiveTaskResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks/mine")
@Tag(name = "Tasks", description = "CRUD задач проекта; видимость и права зависят от роли текущего пользователя в project_members")
public class MyTasksController {

    private final TaskService taskService;

    public MyTasksController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    @Operation(summary = "Мои активные задачи во всех проектах",
            description = "assignee = текущий пользователь, статус не DONE/REJECTED; сортировка — urgency по убыванию, "
                    + "затем due_date по возрастанию (без срока — в конце); page size фиксирован = 8")
    public ResponseEntity<PageResponse<MyActiveTaskResponse>> myActiveTasks(
            @AuthenticationPrincipal User currentUser,
            @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(taskService.listMyActiveTasks(currentUser, page));
    }
}
