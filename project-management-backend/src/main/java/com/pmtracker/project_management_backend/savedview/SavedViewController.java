package com.pmtracker.project_management_backend.savedview;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.savedview.dto.SavedViewRequest;
import com.pmtracker.project_management_backend.savedview.dto.SavedViewResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/views/{id}")
@Tag(name = "Saved views", description = "Личные сохранённые представления списка задач проекта")
public class SavedViewController {

    private final SavedViewService savedViewService;

    public SavedViewController(SavedViewService savedViewService) {
        this.savedViewService = savedViewService;
    }

    @PutMapping
    @Operation(summary = "Перезаписать представление текущими фильтрами",
            description = "PUT, а не PATCH: обновление здесь — это «запомни то, что сейчас на экране», "
                    + "и снятый в интерфейсе фильтр обязан исчезнуть из представления. С семантикой "
                    + "PATCH («отсутствующее поле не трогать») снять фильтр было бы нечем. "
                    + "Чужое представление — 404")
    public ResponseEntity<SavedViewResponse> update(@AuthenticationPrincipal User currentUser,
                                                      @PathVariable UUID id,
                                                      @Valid @RequestBody SavedViewRequest request) {
        return ResponseEntity.ok(savedViewService.update(currentUser, id, request));
    }

    @DeleteMapping
    @Operation(summary = "Удалить представление",
            description = "Удаляется только закладка; задачи, тэги и категории, на которые она ссылалась, "
                    + "не трогаются. Чужое представление — 404")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        savedViewService.delete(currentUser, id);
        return ResponseEntity.noContent().build();
    }
}
