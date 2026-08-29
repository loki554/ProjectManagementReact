package com.pmtracker.project_management_backend.task;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.dto.PageResponse;
import com.pmtracker.project_management_backend.task.dto.CreateTaskRequest;
import com.pmtracker.project_management_backend.task.dto.TaskResponse;
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
@RequestMapping("/api/projects/{projectId}/tasks")
@Tag(name = "Tasks", description = "CRUD задач проекта; видимость и права зависят от роли текущего пользователя в project_members")
public class ProjectTaskController {

    private final TaskService taskService;

    public ProjectTaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    @Operation(summary = "Создать задачу", description = "Top-level задача проекта (parentTaskId = null); OWNER/ADMIN/MEMBER")
    public ResponseEntity<TaskResponse> create(@AuthenticationPrincipal User currentUser,
                                                @PathVariable UUID projectId,
                                                @Valid @RequestBody CreateTaskRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(taskService.create(currentUser, projectId, request));
    }

    @GetMapping
    @Operation(summary = "Список задач проекта (страница)",
            description = "Фильтры: search (подстрока названия), status, assigneeId либо unassigned, tagId, "
                    + "categoryId либо uncategorized, parentId. Без parentId — только top-level задачи "
                    + "(parent_task_id IS NULL). Сортировка: sort (ключ) + direction; page начинается с 0, "
                    + "size по умолчанию 50 и не больше 200. Доске нужен полный набор задач — для неё "
                    + "отдельный /board")
    public ResponseEntity<PageResponse<TaskResponse>> list(@AuthenticationPrincipal User currentUser,
                                                     @PathVariable UUID projectId,
                                                     @RequestParam(required = false) String search,
                                                     @RequestParam(required = false) TaskStatus status,
                                                     @RequestParam(required = false) UUID assigneeId,
                                                     @RequestParam(defaultValue = "false") boolean unassigned,
                                                     @RequestParam(required = false) UUID tagId,
                                                     @RequestParam(required = false) UUID categoryId,
                                                     @RequestParam(defaultValue = "false") boolean uncategorized,
                                                     @RequestParam(required = false) UUID parentId,
                                                     @RequestParam(defaultValue = "NUMBER") TaskSortKey sort,
                                                     @RequestParam(defaultValue = "false") boolean descending,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "0") int size) {
        TaskListQuery query = new TaskListQuery(parentId, search, status, assigneeId, unassigned,
                tagId, categoryId, uncategorized, sort, descending);
        return ResponseEntity.ok(taskService.list(currentUser, projectId, query, page, size));
    }

    @GetMapping("/board")
    @Operation(summary = "Задачи для канбан-доски",
            description = "Все top-level задачи проекта одним массивом, в порядке position. Намеренно без "
                    + "пагинации: доска считает позицию перетаскиваемой карточки по её соседям и работает "
                    + "только с полной колонкой")
    public ResponseEntity<List<TaskResponse>> board(@AuthenticationPrincipal User currentUser,
                                                      @PathVariable UUID projectId) {
        return ResponseEntity.ok(taskService.listBoard(currentUser, projectId));
    }

    @GetMapping("/by-number/{taskNumber}")
    @Operation(summary = "Детали задачи по её порядковому номеру в проекте",
            description = "Используется фронтендом для читаемых URL (/projects/{slug}/tasks/{taskNumber})")
    public ResponseEntity<TaskResponse> getByNumber(@AuthenticationPrincipal User currentUser,
                                                      @PathVariable UUID projectId,
                                                      @PathVariable int taskNumber) {
        return ResponseEntity.ok(taskService.getByProjectAndNumber(currentUser, projectId, taskNumber));
    }
}
