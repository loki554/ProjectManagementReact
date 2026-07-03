package com.pmtracker.project_management_backend.auth;

import com.pmtracker.project_management_backend.auth.dto.RegisterRequest;
import com.pmtracker.project_management_backend.common.exception.EmailAlreadyExistsException;
import com.pmtracker.project_management_backend.common.exception.InvalidOrExpiredTokenException;
import com.pmtracker.project_management_backend.mail.MailService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class AuthService {

    private static final int VERIFICATION_TOKEN_TTL_HOURS = 24;

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository verificationTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;

    public AuthService(UserRepository userRepository,
                        EmailVerificationTokenRepository verificationTokenRepository,
                        PasswordEncoder passwordEncoder,
                        MailService mailService) {
        this.userRepository = userRepository;
        this.verificationTokenRepository = verificationTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
    }

    @Transactional
    public void register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }

        User user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setLastName(request.lastName());
        user.setFirstName(request.firstName());
        user.setPatronymic(request.patronymic());
        user.setEmailVerified(false);
        userRepository.save(user);

        issueAndSendVerificationToken(user);
    }

    @Transactional
    public void verifyEmail(String tokenValue) {
        UUID token = parseToken(tokenValue);

        EmailVerificationToken verificationToken = verificationTokenRepository.findByToken(token)
                .orElseThrow(InvalidOrExpiredTokenException::new);

        if (verificationToken.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidOrExpiredTokenException();
        }

        User user = verificationToken.getUser();
        user.setEmailVerified(true);
        userRepository.save(user);

        verificationTokenRepository.delete(verificationToken);
    }

    @Transactional
    public void resendVerification(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            if (user.isEmailVerified()) {
                return;
            }
            verificationTokenRepository.deleteByUser(user);
            issueAndSendVerificationToken(user);
        });
    }

    private void issueAndSendVerificationToken(User user) {
        EmailVerificationToken verificationToken = new EmailVerificationToken();
        verificationToken.setUser(user);
        verificationToken.setToken(UUID.randomUUID());
        verificationToken.setExpiresAt(Instant.now().plus(VERIFICATION_TOKEN_TTL_HOURS, ChronoUnit.HOURS));
        verificationTokenRepository.save(verificationToken);

        mailService.sendVerificationEmail(user.getEmail(), verificationToken.getToken());
    }

    private UUID parseToken(String tokenValue) {
        try {
            return UUID.fromString(tokenValue);
        } catch (IllegalArgumentException e) {
            throw new InvalidOrExpiredTokenException();
        }
    }
}
