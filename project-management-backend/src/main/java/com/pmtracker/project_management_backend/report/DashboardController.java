package com.pmtracker.project_management_backend.report;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.report.dto.DashboardResponse;
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
@RequestMapping("/api/projects/{projectId}/dashboard")
@Tag(name = "Reports", description = "Отчёты по времени и дашборд проекта")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    @Operation(summary = "Дашборд проекта",
            description = "Доступно любому участнику, включая VIEWER. Распределения по статусам и "
                    + "исполнителям и среднее время в статусе считаются по всему проекту и от спринта "
                    + "не зависят; sprintId выбирает спринт для burndown — без него берётся идущий, а "
                    + "если такого нет, последний завершённый. burndown = null, если спринтов в "
                    + "проекте нет вовсе. Точки будущих дней приезжают с remaining = null: это «данных "
                    + "ещё нет», а не «всё закрыто»")
    public ResponseEntity<DashboardResponse> dashboard(@AuthenticationPrincipal User currentUser,
                                                        @PathVariable UUID projectId,
                                                        @RequestParam(required = false) UUID sprintId) {
        return ResponseEntity.ok(dashboardService.dashboard(currentUser, projectId, sprintId));
    }
}
