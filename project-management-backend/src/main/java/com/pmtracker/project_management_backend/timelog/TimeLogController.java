package com.pmtracker.project_management_backend.timelog;

import com.pmtracker.project_management_backend.auth.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/time-logs")
@Tag(name = "Time logs", description = "Учёт времени по задаче")
public class TimeLogController {

    private final TimeLogService timeLogService;

    public TimeLogController(TimeLogService timeLogService) {
        this.timeLogService = timeLogService;
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить запись времени", description = "Автор записи, либо OWNER/ADMIN проекта; иначе 403 NOT_TIME_LOG_OWNER")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        timeLogService.delete(currentUser, id);
        return ResponseEntity.noContent().build();
    }
}
