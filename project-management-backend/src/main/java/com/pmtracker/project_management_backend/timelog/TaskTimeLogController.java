package com.pmtracker.project_management_backend.timelog;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.timelog.dto.CreateTimeLogRequest;
import com.pmtracker.project_management_backend.timelog.dto.TimeLogResponse;
import com.pmtracker.project_management_backend.timelog.dto.TimeLogsResponse;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/tasks/{id}/time-logs")
@Tag(name = "Time logs", description = "Учёт времени по задаче")
public class TaskTimeLogController {

    private final TimeLogService timeLogService;

    public TaskTimeLogController(TimeLogService timeLogService) {
        this.timeLogService = timeLogService;
    }

    @PostMapping
    @Operation(summary = "Залогировать время", description = "user = текущий пользователь (за себя, не за другого участника); OWNER/ADMIN/MEMBER, не VIEWER")
    public ResponseEntity<TimeLogResponse> create(@AuthenticationPrincipal User currentUser,
                                                    @PathVariable UUID id,
                                                    @Valid @RequestBody CreateTimeLogRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(timeLogService.create(currentUser, id, request));
    }

    @GetMapping
    @Operation(summary = "Список записей времени по задаче", description = "Доступно любому участнику проекта, включая VIEWER; включает totalHours")
    public ResponseEntity<TimeLogsResponse> list(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        return ResponseEntity.ok(timeLogService.list(currentUser, id));
    }
}
