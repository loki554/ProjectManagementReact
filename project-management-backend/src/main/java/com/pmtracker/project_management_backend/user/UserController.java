package com.pmtracker.project_management_backend.user;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.dto.UserSummary;
import com.pmtracker.project_management_backend.user.dto.UpdateProfileRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    // GET без обращения к сервису: JwtAuthenticationFilter уже подгрузил полноценного
    // User из БД в SecurityContext, тут просто нечего кроме маппинга в DTO делать.
    @GetMapping("/me")
    public ResponseEntity<UserSummary> me(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(UserSummary.from(currentUser));
    }

    @PatchMapping("/me")
    public ResponseEntity<UserSummary> updateMe(@AuthenticationPrincipal User currentUser,
                                                 @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(userService.updateProfile(currentUser, request));
    }
}
