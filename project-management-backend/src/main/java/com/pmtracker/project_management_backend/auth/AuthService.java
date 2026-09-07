package com.pmtracker.project_management_backend.auth;

import com.pmtracker.project_management_backend.auth.dto.AuthResponse;
import com.pmtracker.project_management_backend.auth.dto.LoginRequest;
import com.pmtracker.project_management_backend.auth.dto.RegisterRequest;
import com.pmtracker.project_management_backend.auth.dto.UserSummary;
import com.pmtracker.project_management_backend.common.SecureTokens;
import com.pmtracker.project_management_backend.common.exception.EmailNotVerifiedException;
import com.pmtracker.project_management_backend.common.exception.InvalidCredentialsException;
import com.pmtracker.project_management_backend.common.exception.UsernameAlreadyTakenException;
import com.pmtracker.project_management_backend.common.exception.InvalidOrExpiredTokenException;
import com.pmtracker.project_management_backend.common.exception.InvalidRefreshTokenException;
import com.pmtracker.project_management_backend.config.JwtProperties;
import com.pmtracker.project_management_backend.mail.AccountAlreadyExistsEmailRequestedEvent;
import com.pmtracker.project_management_backend.common.EmailNormalizer;
import com.pmtracker.project_management_backend.common.UsernameNormalizer;
import com.pmtracker.project_management_backend.mail.PasswordResetEmailRequestedEvent;
import com.pmtracker.project_management_backend.mail.VerificationEmailRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final int VERIFICATION_TOKEN_TTL_HOURS = 24;

    /**
     * Час против 24 у верификации: перехваченная ссылка сброса — это сразу чужой аккаунт,
     * а ссылка подтверждения сама по себе не даёт войти. Час покрывает сценарий «запросил,
     * отвлёкся, вернулся», но не оставляет рабочую ссылку в почтовом ящике на сутки.
     */
    private static final int PASSWORD_RESET_TOKEN_TTL_HOURS = 1;

    /** 64 байта → 86 символов Base64URL; на эту длину рассчитан @Size в RefreshRequest. */
    private static final int REFRESH_TOKEN_BYTES = 64;

    /** 32 байта → 43 символа, 256 бит энтропии: перебрать ссылку сброса нереально. */
    private static final int PASSWORD_RESET_TOKEN_BYTES = 32;

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository verificationTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;

    public AuthService(UserRepository userRepository,
                        EmailVerificationTokenRepository verificationTokenRepository,
                        PasswordResetTokenRepository passwordResetTokenRepository,
                        RefreshTokenRepository refreshTokenRepository,
                        PasswordEncoder passwordEncoder,
                        ApplicationEventPublisher eventPublisher,
                        JwtService jwtService,
                        JwtProperties jwtProperties) {
        this.userRepository = userRepository;
        this.verificationTokenRepository = verificationTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
    }

    /**
     * Регистрация. Ответ контроллера одинаков и для свободного, и для занятого адреса — раньше
     * занятый давал 409 EMAIL_ALREADY_EXISTS, и это был бесплатный способ перебором выяснять,
     * зарегистрирован ли конкретный человек (при том, что соседний /resend-verification такую
     * проверку аккуратно не давал). Тому, кто действительно владеет ящиком, знать о существующем
     * аккаунте по-прежнему нужно — но узнаёт он это из письма, а не из HTTP-ответа.
     */
    @Transactional
    public void register(RegisterRequest request) {
        // Пароль хешируется в обеих ветках, и это не бессмысленная работа: BCrypt — единственная
        // заметно долгая операция в этом методе (сотни миллисекунд), и пропуск её на занятом
        // адресе превратил бы время ответа в тот же самый индикатор существования аккаунта,
        // который мы только что убрали из тела ответа.
        String passwordHash = passwordEncoder.encode(request.password());

        // Адрес канонизируется до всех проверок и записи: без этого Ivan@Company.com и
        // ivan@company.com — два аккаунта на один ящик (см. EmailNormalizer).
        String email = EmailNormalizer.normalize(request.email());
        String username = UsernameNormalizer.normalize(request.username());

        // Никнейм проверяется ПЕРВЫМ, до ветки с занятым адресом, и порядок здесь не вкусовой.
        // Наоборот эта пара проверок стала бы тем самым индикатором существования аккаунта,
        // который выше так старательно убран: занятый никнейм при свободном адресе отвечал бы
        // 409, а тот же никнейм при занятом адресе — успехом, и разница в ответе выдавала бы
        // владельца ящика. В нынешнем порядке ответ на занятый адрес одинаков всегда.
        if (userRepository.existsByUsername(username)) {
            throw new UsernameAlreadyTakenException();
        }

        if (userRepository.existsByEmail(email)) {
            eventPublisher.publishEvent(new AccountAlreadyExistsEmailRequestedEvent(email));
            return;
        }

        User user = new User();
        user.setEmail(email);
        user.setUsername(username);
        user.setPasswordHash(passwordHash);
        user.setLastName(request.lastName());
        user.setFirstName(request.firstName());
        user.setPatronymic(request.patronymic());
        user.setEmailVerified(false);
        saveWithUniqueUsername(user);

        issueAndSendVerificationToken(user);
    }

    /**
     * Вставка с переводом гонки по никнейму в внятный 409.
     * <p>
     * Проверка {@code existsByUsername} выше отвечает за нормальный случай, но между ней и
     * вставкой помещается чужая регистрация с тем же никнеймом. Настоящая гарантия — уникальный
     * индекс (V28), и без этой обёртки его срабатывание уходило бы в {@code handleUnexpected}
     * пятисоткой: человек видел бы «внутренняя ошибка» там, где ему всего лишь надо выбрать
     * другое имя. {@code saveAndFlush}, а не {@code save}, именно ради этого — иначе нарушение
     * всплыло бы на коммите, уже за пределами try.
     */
    private void saveWithUniqueUsername(User user) {
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new UsernameAlreadyTakenException();
        }
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

        // Адрес подтверждён — значит, приглашения, выписанные на него до появления аккаунта,
        // можно наконец принять (см. EmailVerifiedEvent и ProjectInvitationService).
        eventPublisher.publishEvent(new EmailVerifiedEvent(user.getId(), user.getEmail()));
    }

    @Transactional
    public void resendVerification(String email) {
        userRepository.findByEmail(EmailNormalizer.normalize(email)).ifPresent(user -> {
            if (user.isEmailVerified()) {
                return;
            }
            verificationTokenRepository.deleteByUser(user);
            issueAndSendVerificationToken(user);
        });
    }

    /**
     * Запрос ссылки на смену пароля. Никогда не сообщает вызывающему, существует ли аккаунт:
     * ответ контроллера одинаков всегда (см. AuthController.forgotPassword), а здесь просто
     * ничего не происходит для незнакомого адреса.
     *
     * Неподтверждённый email при этом не помеха: переход по ссылке из письма сам по себе
     * доказывает владение ящиком, поэтому reset заодно подтверждает адрес (см. resetPassword).
     */
    @Transactional
    public void forgotPassword(String email) {
        userRepository.findByEmail(EmailNormalizer.normalize(email)).ifPresent(user -> {
            // Прошлые ссылки гасим: их могло накопиться сколько угодно (эндпоинт публичный),
            // и каждая живая — это ещё одна рабочая дверь в аккаунт.
            passwordResetTokenRepository.deleteByUser(user);

            String rawToken = SecureTokens.generate(PASSWORD_RESET_TOKEN_BYTES);
            PasswordResetToken token = new PasswordResetToken();
            token.setUser(user);
            token.setTokenHash(SecureTokens.sha256Hex(rawToken));
            token.setExpiresAt(Instant.now().plus(PASSWORD_RESET_TOKEN_TTL_HOURS, ChronoUnit.HOURS));
            passwordResetTokenRepository.save(token);

            eventPublisher.publishEvent(new PasswordResetEmailRequestedEvent(user.getEmail(), rawToken));
        });
    }

    /**
     * Смена пароля по ссылке из письма.
     *
     * Помимо собственно пароля делает ещё две вещи. Гасит все refresh-токены пользователя:
     * сброс пароля — штатная реакция на «кажется, меня взломали», и он обязан выкидывать чужие
     * сессии, иначе смена пароля защищает только от повторного входа, а уже открытая чужая
     * сессия живёт своей жизнью. И подтверждает email, если тот ещё не подтверждён: переход по
     * ссылке доказывает владение ящиком ровно так же, как ссылка верификации, а оставлять
     * человека с новым паролем и всё ещё запертым входом было бы просто издевательством.
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        PasswordResetToken token = passwordResetTokenRepository.findByTokenHash(SecureTokens.sha256Hex(rawToken))
                .orElseThrow(InvalidOrExpiredTokenException::new);

        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidOrExpiredTokenException();
        }

        User user = token.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setEmailVerified(true);
        userRepository.save(user);

        // Токен одноразовый: удаляем все ссылки этого пользователя, а не только использованную.
        passwordResetTokenRepository.deleteByUser(user);
        int revokedCount = refreshTokenRepository.revokeAllByUserId(user.getId());
        log.info("Password reset completed for user {}, revoked {} active refresh token(s)",
                user.getId(), revokedCount);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(EmailNormalizer.normalize(request.email()))
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        if (!user.isEmailVerified()) {
            throw new EmailNotVerifiedException();
        }

        return issueTokenPair(user);
    }

    /**
     * Меняет refresh-токен на новую пару токенов и попутно ловит повторное использование
     * уже отозванного токена.
     *
     * noRollbackFor: в ветке reuse мы гасим все токены пользователя и тут же бросаем
     * InvalidRefreshTokenException — без этого флага Spring откатил бы транзакцию вместе
     * с отзывом, и защита не сработала бы вовсе. Остальные ветки с этим исключением в БД
     * ничего не пишут, так что для них разницы между коммитом и откатом нет.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public AuthResponse refresh(String rawRefreshToken) {
        RefreshToken existingToken = refreshTokenRepository.findByTokenHash(SecureTokens.sha256Hex(rawRefreshToken))
                .orElseThrow(InvalidRefreshTokenException::new);

        // Токен отозван, и у него есть replacedBy — значит, им уже один раз успешно
        // воспользовались, получили взамен новый, и вот приходят с ним второй раз. Двух
        // живых владельцев одного токена быть не должно: либо утёк наш, либо это тот, кто
        // его украл. Кто именно пришёл — по запросу не понять, поэтому гасим всю цепочку:
        // злоумышленник теряет доступ, пользователь логинится заново.
        //
        // Токены, отозванные логаутом (replacedBy == null), сюда не попадают: новой цепочки
        // из них не выросло, продолжать нечего, а гонка «логаут и параллельный refresh той же
        // вкладки» вполне реальна — разлогинивать за неё на всех устройствах было бы грубо.
        if (existingToken.isRevoked() && existingToken.getReplacedBy() != null) {
            UUID userId = existingToken.getUser().getId();
            int revokedCount = refreshTokenRepository.revokeAllByUserId(userId);
            log.warn("Refresh token reuse detected: token {} of user {} had already been rotated, "
                    + "revoked {} active token(s) of this user", existingToken.getId(), userId, revokedCount);
            throw new InvalidRefreshTokenException();
        }

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
        refreshTokenRepository.findByTokenHash(SecureTokens.sha256Hex(rawRefreshToken))
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
        String rawValue = SecureTokens.generate(REFRESH_TOKEN_BYTES);

        RefreshToken entity = new RefreshToken();
        entity.setUser(user);
        entity.setTokenHash(SecureTokens.sha256Hex(rawValue));
        entity.setExpiresAt(Instant.now().plus(jwtProperties.getRefreshTokenTtlDays(), ChronoUnit.DAYS));
        refreshTokenRepository.save(entity);

        return new GeneratedRefreshToken(entity, rawValue);
    }

    private record GeneratedRefreshToken(RefreshToken entity, String rawValue) {
    }

    private void issueAndSendVerificationToken(User user) {
        EmailVerificationToken verificationToken = new EmailVerificationToken();
        verificationToken.setUser(user);
        verificationToken.setToken(UUID.randomUUID());
        verificationToken.setExpiresAt(Instant.now().plus(VERIFICATION_TOKEN_TTL_HOURS, ChronoUnit.HOURS));
        verificationTokenRepository.save(verificationToken);

        // Не отправляем письмо здесь: мы внутри @Transactional, а SMTP — внешняя система, которая
        // умеет тормозить и падать. Событие уедет в VerificationMailDispatcher уже после коммита
        // и в отдельном потоке; там же ретраи.
        eventPublisher.publishEvent(new VerificationEmailRequestedEvent(user.getEmail(), verificationToken.getToken()));
    }

    private UUID parseToken(String tokenValue) {
        try {
            return UUID.fromString(tokenValue);
        } catch (IllegalArgumentException e) {
            throw new InvalidOrExpiredTokenException();
        }
    }
}
