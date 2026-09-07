package com.pmtracker.project_management_backend.sprint;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.sprint.dto.SprintRequest;
import com.pmtracker.project_management_backend.sprint.dto.SprintResponse;
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
@RequestMapping("/api/projects/{projectId}/sprints")
@Tag(name = "Sprints", description = "Спринты и майлстоуны проекта: окно времени с набором задач")
public class ProjectSprintController {

    private final SprintService sprintService;

    public ProjectSprintController(SprintService sprintService) {
        this.sprintService = sprintService;
    }

    @PostMapping
    @Operation(summary = "Завести спринт",
            description = "OWNER/ADMIN. Имя уникально в проекте (409 DUPLICATE_SPRINT_NAME), окончание "
                    + "не раньше начала (400 SPRINT_DATES_INVALID). Спринт заводится в статусе PLANNED — "
                    + "начинают его отдельным действием. Майлстоун — тот же спринт с одинаковыми "
                    + "startDate и endDate")
    public ResponseEntity<SprintResponse> create(@AuthenticationPrincipal User currentUser,
                                                  @PathVariable UUID projectId,
                                                  @Valid @RequestBody SprintRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sprintService.create(currentUser, projectId, request));
    }

    @GetMapping
    @Operation(summary = "Спринты проекта",
            description = "Доступно любому участнику, включая VIEWER. Порядок: активный, затем "
                    + "запланированные (ближайший сверху), затем завершённые (последний сверху). У "
                    + "каждого спринта — счётчики taskCount и closedTaskCount (закрытая задача это "
                    + "DONE или REJECTED). Сами задачи спринта приезжают обычным списком задач с "
                    + "фильтром sprintId")
    public ResponseEntity<List<SprintResponse>> list(@AuthenticationPrincipal User currentUser,
                                                      @PathVariable UUID projectId) {
        return ResponseEntity.ok(sprintService.list(currentUser, projectId));
    }
}
