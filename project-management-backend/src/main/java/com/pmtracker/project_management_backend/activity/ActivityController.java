package com.pmtracker.project_management_backend.activity;

import com.pmtracker.project_management_backend.activity.dto.ActivityResponse;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/activity")
@Tag(name = "Project activity", description = "Лента событий проекта; записывается с момента внедрения ленты")
public class ActivityController {

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping
    @Operation(summary = "Лента активности проекта",
            description = "Свежие события сверху; page size фиксирован = 20. Доступно любому участнику проекта, включая VIEWER. "
                    + "taskId сужает ленту до событий одной задачи (вкладка «Активность» на странице задачи)")
    public ResponseEntity<PageResponse<ActivityResponse>> list(@AuthenticationPrincipal User currentUser,
                                                               @PathVariable UUID projectId,
                                                               @RequestParam(required = false) UUID taskId,
                                                               @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(activityService.list(currentUser, projectId, taskId, page));
    }
}
