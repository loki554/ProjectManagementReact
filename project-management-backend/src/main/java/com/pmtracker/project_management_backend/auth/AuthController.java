package com.pmtracker.project_management_backend.auth;

import com.pmtracker.project_management_backend.auth.dto.AuthResponse;
import com.pmtracker.project_management_backend.auth.dto.LoginRequest;
import com.pmtracker.project_management_backend.auth.dto.MessageResponse;
import com.pmtracker.project_management_backend.auth.dto.RefreshRequest;
import com.pmtracker.project_management_backend.auth.dto.RegisterRequest;
import com.pmtracker.project_management_backend.auth.dto.ResendVerificationRequest;
import com.pmtracker.project_management_backend.auth.dto.UserSummary;
import com.pmtracker.project_management_backend.auth.dto.VerifyEmailRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<MessageResponse> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new MessageResponse("Регистрация прошла успешно. Проверьте почту для подтверждения."));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<MessageResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        authService.verifyEmail(request.token());
        return ResponseEntity.ok(new MessageResponse("Email подтверждён."));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<MessageResponse> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        authService.resendVerification(request.email());
        return ResponseEntity.ok(new MessageResponse(
                "Если аккаунт с таким email существует и ещё не подтверждён, письмо отправлено повторно."));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /**
     * "Кто я" — проверяет, что JWT-фильтр отработал и в SecurityContext лежит текущий пользователь.
     * Понадобится фронтенду при старте приложения, чтобы восстановить сессию по access-токену.
     */
    @GetMapping("/me")
    public ResponseEntity<UserSummary> me(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(UserSummary.from(currentUser));
    }
}
