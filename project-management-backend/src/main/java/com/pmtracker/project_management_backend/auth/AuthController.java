package com.pmtracker.project_management_backend.auth;

import com.pmtracker.project_management_backend.auth.dto.AuthResponse;
import com.pmtracker.project_management_backend.auth.dto.ForgotPasswordRequest;
import com.pmtracker.project_management_backend.auth.dto.LoginRequest;
import com.pmtracker.project_management_backend.auth.dto.MessageResponse;
import com.pmtracker.project_management_backend.auth.dto.RefreshRequest;
import com.pmtracker.project_management_backend.auth.dto.RegisterRequest;
import com.pmtracker.project_management_backend.auth.dto.ResendVerificationRequest;
import com.pmtracker.project_management_backend.auth.dto.ResetPasswordRequest;
import com.pmtracker.project_management_backend.auth.dto.VerifyEmailRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
                .body(new MessageResponse("Registration successful. Check your email to confirm your account."));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<MessageResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        authService.verifyEmail(request.token());
        return ResponseEntity.ok(new MessageResponse("Email confirmed."));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<MessageResponse> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        authService.resendVerification(request.email());
        return ResponseEntity.ok(new MessageResponse(
                "If an account with this email exists and is not yet verified, the email has been resent."));
    }

    // Ответ намеренно одинаков и для существующего, и для незнакомого адреса — иначе этот
    // эндпоинт становится удобной проверялкой «есть ли у вас аккаунт вот этого человека».
    // Тот же приём, что у /resend-verification.
    @PostMapping("/forgot-password")
    public ResponseEntity<MessageResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request.email());
        return ResponseEntity.ok(new MessageResponse(
                "If an account with this email exists, a password reset link has been sent."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<MessageResponse> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.ok(new MessageResponse("Password has been changed. You can now sign in."));
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
}
