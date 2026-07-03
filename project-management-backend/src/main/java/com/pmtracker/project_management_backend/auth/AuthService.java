package com.pmtracker.project_management_backend.auth;

import com.pmtracker.project_management_backend.auth.dto.AuthResponse;
import com.pmtracker.project_management_backend.auth.dto.LoginRequest;
import com.pmtracker.project_management_backend.auth.dto.RegisterRequest;
import com.pmtracker.project_management_backend.auth.dto.UserSummary;
import com.pmtracker.project_management_backend.common.exception.EmailAlreadyExistsException;
import com.pmtracker.project_management_backend.common.exception.EmailNotVerifiedException;
import com.pmtracker.project_management_backend.common.exception.InvalidCredentialsException;
import com.pmtracker.project_management_backend.common.exception.InvalidOrExpiredTokenException;
import com.pmtracker.project_management_backend.common.exception.InvalidRefreshTokenException;
import com.pmtracker.project_management_backend.config.JwtProperties;
import com.pmtracker.project_management_backend.mail.MailService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuthService {

    private static final int VERIFICATION_TOKEN_TTL_HOURS = 24;

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository verificationTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(UserRepository userRepository,
                        EmailVerificationTokenRepository verificationTokenRepository,
                        RefreshTokenRepository refreshTokenRepository,
                        PasswordEncoder passwordEncoder,
                        MailService mailService,
                        JwtService jwtService,
                        JwtProperties jwtProperties) {
        this.userRepository = userRepository;
        this.verificationTokenRepository = verificationTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
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

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        if (!user.isEmailVerified()) {
            throw new EmailNotVerifiedException();
        }

        return issueTokenPair(user);
    }

    @Transactional
    public AuthResponse refresh(String rawRefreshToken) {
        RefreshToken existingToken = refreshTokenRepository.findByTokenHash(hashToken(rawRefreshToken))
                .orElseThrow(InvalidRefreshTokenException::new);

        if (existingToken.isRevoked() || existingToken.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidRefreshTokenException();
        }

        User user = existingToken.getUser();
        String accessToken = jwtService.generateAccessToken(user);
        GeneratedRefreshToken newRefreshToken = createRefreshToken(user);

        existingToken.setRevoked(true);
        existingToken.setReplacedBy(newRefreshToken.entity().getId());
        refreshTokenRepository.save(existingToken);

        return new AuthResponse(accessToken, newRefreshToken.rawValue(), UserSummary.from(user));
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenRepository.findByTokenHash(hashToken(rawRefreshToken))
                .ifPresent(token -> {
                    token.setRevoked(true);
                    refreshTokenRepository.save(token);
                });
    }

    private AuthResponse issueTokenPair(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        GeneratedRefreshToken refreshToken = createRefreshToken(user);
        return new AuthResponse(accessToken, refreshToken.rawValue(), UserSummary.from(user));
    }

    /**
     * Генерирует новый refresh-токен: случайная строка уходит клиенту и нигде не сохраняется,
     * в БД пишется только её SHA-256 хеш — так утечка базы не даёт готовых токенов для входа.
     */
    private GeneratedRefreshToken createRefreshToken(User user) {
        byte[] randomBytes = new byte[64];
        secureRandom.nextBytes(randomBytes);
        String rawValue = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        RefreshToken entity = new RefreshToken();
        entity.setUser(user);
        entity.setTokenHash(hashToken(rawValue));
        entity.setExpiresAt(Instant.now().plus(jwtProperties.getRefreshTokenTtlDays(), ChronoUnit.DAYS));
        refreshTokenRepository.save(entity);

        return new GeneratedRefreshToken(entity, rawValue);
    }

    private record GeneratedRefreshToken(RefreshToken entity, String rawValue) {
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен в JVM", e);
        }
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
