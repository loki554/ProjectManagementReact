package com.pmtracker.project_management_backend.sprint;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.sprint.dto.CompleteSprintRequest;
import com.pmtracker.project_management_backend.sprint.dto.CompleteSprintResponse;
import com.pmtracker.project_management_backend.sprint.dto.SprintRequest;
import com.pmtracker.project_management_backend.sprint.dto.SprintResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/sprints/{id}")
@Tag(name = "Sprints", description = "Спринты и майлстоуны проекта: окно времени с набором задач")
public class SprintController {

    private final SprintService sprintService;

    public SprintController(SprintService sprintService) {
        this.sprintService = sprintService;
    }

    // PUT, а не PATCH: у спринта четыре поля, и правка его карточки — это та же форма
    // целиком, где снятая цель обязана доехать снятой (см. SprintRequest).
    @PutMapping
    @Operation(summary = "Переписать карточку спринта",
            description = "OWNER/ADMIN. Имя, цель и окно дат; статус этим запросом не меняется — для "
                    + "него есть /start и /complete. Править можно и завершённый спринт: запрещено "
                    + "менять его состав, а не исправлять в нём опечатку")
    public ResponseEntity<SprintResponse> update(@AuthenticationPrincipal User currentUser,
                                                  @PathVariable UUID id,
                                                  @Valid @RequestBody SprintRequest request) {
        return ResponseEntity.ok(sprintService.update(currentUser, id, request));
    }

    @PostMapping("/start")
    @Operation(summary = "Начать спринт",
            description = "PLANNED → ACTIVE. Активный спринт в проекте может быть только один "
                    + "(409 SPRINT_ALREADY_ACTIVE); начать уже начатый или завершённый — 400 "
                    + "SPRINT_TRANSITION_INVALID. OWNER/ADMIN")
    public ResponseEntity<SprintResponse> start(@AuthenticationPrincipal User currentUser,
                                                 @PathVariable UUID id) {
        return ResponseEntity.ok(sprintService.start(currentUser, id));
    }

    @PostMapping("/complete")
    @Operation(summary = "Завершить спринт",
            description = "ACTIVE → COMPLETED. Незакрытые задачи (не DONE и не REJECTED) переезжают в "
                    + "спринт moveUnfinishedToSprintId либо, если он не задан, в бэклог — остаться в "
                    + "завершённом спринте они не могут. Целевой спринт должен быть незакрытым и из "
                    + "того же проекта. В ответе — спринт и число переехавших задач. OWNER/ADMIN")
    public ResponseEntity<CompleteSprintResponse> complete(@AuthenticationPrincipal User currentUser,
                                                            @PathVariable UUID id,
                                                            @RequestBody(required = false) CompleteSprintRequest request) {
        CompleteSprintRequest body = request != null ? request : new CompleteSprintRequest(null);
        return ResponseEntity.ok(sprintService.complete(currentUser, id, body));
    }

    @DeleteMapping
    @Operation(summary = "Удалить спринт",
            description = "OWNER/ADMIN. Задачи не удаляются: ссылка на спринт обнуляется, и весь его "
                    + "состав возвращается в бэклог. Удалить можно спринт в любом статусе")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        sprintService.delete(currentUser, id);
        return ResponseEntity.noContent().build();
    }
}
