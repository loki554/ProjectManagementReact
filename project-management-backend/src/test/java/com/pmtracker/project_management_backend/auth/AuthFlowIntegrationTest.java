package com.pmtracker.project_management_backend.auth;

import com.jayway.jsonpath.JsonPath;
import com.pmtracker.project_management_backend.support.IntegrationTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Сквозной тест auth-флоу: регистрация → письмо → подтверждение → вход → обновление токена →
 * ротация → выход, плюс негативные ветки (чужой, битый, просроченный токен, неподтверждённый
 * email, повторное использование уже провёрнутого refresh-токена).
 * <p>
 * Всё проверяется через HTTP-контракт, а не вызовами сервисов: смысл именно в том, чтобы
 * пройти тот же путь, что проходит фронтенд, — вместе с фильтрами безопасности, валидацией
 * DTO, настоящими транзакциями и реальной отправкой письма. Единственное место, куда тест
 * лезет мимо API, — подкрутка expires_at в БД: дождаться истечения суточного токена честно
 * нельзя, а ветка «просрочено» слишком важна, чтобы остаться непроверенной.
 */
class AuthFlowIntegrationTest extends IntegrationTest {

    private static final String EMAIL = "flow.user@example.com";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String NEW_PASSWORD = "another-correct-horse";

    /** Токен подтверждения — UUID в открытом виде (см. EmailVerificationToken). */
    private static final Pattern VERIFY_LINK =
            Pattern.compile("/verify-email\\?token=([0-9a-fA-F-]{36})");

    /** Токен сброса — 32 случайных байта в Base64URL без паддинга (см. AuthService). */
    private static final Pattern RESET_LINK =
            Pattern.compile("/reset-password\\?token=([A-Za-z0-9_-]+)");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------- регистрация

    @Nested
    @DisplayName("POST /api/auth/register")
    class Registration {

        @Test
        @DisplayName("создаёт неподтверждённого пользователя и присылает письмо с рабочей ссылкой")
        void registersUserAndSendsVerificationEmail() throws Exception {
            register(EMAIL, PASSWORD)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.message").exists());

            assertThat(emailVerifiedOf(EMAIL)).isFalse();

            MimeMessage email = awaitSingleEmail();
            assertThat(email.getAllRecipients()[0].toString()).isEqualTo(EMAIL);
            assertThat(email.getSubject()).contains("Подтверждение регистрации");
            // Ссылка ведёт на фронтенд (app.frontend.base-url), а не на бэкенд: подтверждает
            // страница SPA, которая уже сама зовёт /api/auth/verify-email.
            assertThat(bodyOf(email)).contains("http://localhost:5173/verify-email?token=");
            assertThat(extractVerificationToken(email)).isNotBlank();
        }

        @Test
        @DisplayName("на занятый адрес отвечает ровно тем же, но письмо шлёт другое")
        void registeringExistingEmailDoesNotRevealTheAccount() throws Exception {
            registerAndVerify(EMAIL, PASSWORD);

            // Ответ обязан быть неотличим от регистрации на свободный адрес — иначе форма
            // регистрации превращается в проверялку «есть ли у вас аккаунт вот этого человека».
            // Никнейм здесь свой: чужой человек, пробующий занятый адрес, придумывает своё имя,
            // а не повторяет чужое (совпадение никнеймов — отдельный отказ, см.
            // UsernameIntegrationTest, и он одинаков что при занятом адресе, что при свободном).
            register(EMAIL, "some-other-password", "someone-else")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.message").exists());

            MimeMessage email = awaitSingleEmail();
            assertThat(email.getSubject()).contains("Попытка регистрации");
            assertThat(bodyOf(email)).doesNotContain("/verify-email?token=");

            // Второго пользователя не появилось, и пароль первого не подменился.
            assertThat(countUsers(EMAIL)).isEqualTo(1);
            login(EMAIL, PASSWORD).andExpect(status().isOk());
        }

        @Test
        @DisplayName("короткий пароль отбивается валидацией, письма нет")
        void rejectsWeakPassword() throws Exception {
            register(EMAIL, "short")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.message").value(containsString("password")));

            assertThat(countUsers(EMAIL)).isZero();
            assertNoEmailSent();
        }
    }

    // ----------------------------------------------------------------- подтверждение

    @Nested
    @DisplayName("POST /api/auth/verify-email")
    class EmailVerification {

        @Test
        @DisplayName("подтверждает адрес и гасит токен — вторично та же ссылка не работает")
        void verifiesEmailOnceAndBurnsTheToken() throws Exception {
            register(EMAIL, PASSWORD).andExpect(status().isCreated());
            String token = extractVerificationToken(awaitSingleEmail());

            verifyEmail(token).andExpect(status().isOk());
            assertThat(emailVerifiedOf(EMAIL)).isTrue();

            verifyEmail(token)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
        }

        @Test
        @DisplayName("чужой токен — 400, а не 500 и не подсказка о существовании аккаунта")
        void rejectsUnknownToken() throws Exception {
            verifyEmail(UUID.randomUUID().toString())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
        }

        @Test
        @DisplayName("токен не UUID — тот же 400 INVALID_TOKEN, а не падение на разборе")
        void rejectsMalformedToken() throws Exception {
            verifyEmail("not-a-uuid-at-all")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
        }

        @Test
        @DisplayName("просроченный токен не подтверждает адрес")
        void rejectsExpiredToken() throws Exception {
            register(EMAIL, PASSWORD).andExpect(status().isCreated());
            String token = extractVerificationToken(awaitSingleEmail());

            expireAll("email_verification_tokens");

            verifyEmail(token)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
            assertThat(emailVerifiedOf(EMAIL)).isFalse();
        }

        @Test
        @DisplayName("повторная отправка выдаёт новый токен и убивает старый")
        void resendInvalidatesThePreviousToken() throws Exception {
            register(EMAIL, PASSWORD).andExpect(status().isCreated());
            String firstToken = extractVerificationToken(awaitSingleEmail());
            clearMailbox();

            resendVerification(EMAIL).andExpect(status().isOk());
            String secondToken = extractVerificationToken(awaitSingleEmail());

            assertThat(secondToken).isNotEqualTo(firstToken);
            verifyEmail(firstToken)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
            verifyEmail(secondToken).andExpect(status().isOk());
        }

        @Test
        @DisplayName("незнакомому и уже подтверждённому адресу отвечает так же, но письма не шлёт")
        void resendNeverRevealsWhetherTheAccountExists() throws Exception {
            resendVerification("nobody@example.com").andExpect(status().isOk());
            assertNoEmailSent();

            registerAndVerify(EMAIL, PASSWORD);

            resendVerification(EMAIL).andExpect(status().isOk());
            assertNoEmailSent();
        }
    }

    // ------------------------------------------------------------------------- вход

    @Nested
    @DisplayName("POST /api/auth/login")
    class Login {

        @Test
        @DisplayName("выдаёт пару токенов, и access-токен реально открывает защищённый эндпоинт")
        void issuesUsableTokenPair() throws Exception {
            registerAndVerify(EMAIL, PASSWORD);

            String response = login(EMAIL, PASSWORD)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                    .andExpect(jsonPath("$.user.email").value(EMAIL))
                    .andReturn().getResponse().getContentAsString();

            // Смысл всего флоу — вот эта строка: полученный токен работает на настоящем
            // защищённом эндпоинте, пройдя JwtAuthenticationFilter и security-цепочку.
            mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken(response)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value(EMAIL));
        }

        @Test
        @DisplayName("без токена защищённый эндпоинт отдаёт 401 UNAUTHENTICATED")
        void protectedEndpointRequiresToken() throws Exception {
            mockMvc.perform(get("/api/users/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
        }

        @Test
        @DisplayName("неподтверждённый email — 403 EMAIL_NOT_VERIFIED, а не вход")
        void refusesUnverifiedAccount() throws Exception {
            register(EMAIL, PASSWORD).andExpect(status().isCreated());
            awaitSingleEmail();

            login(EMAIL, PASSWORD)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("EMAIL_NOT_VERIFIED"));
        }

        @Test
        @DisplayName("неверный пароль и незнакомый адрес неотличимы: оба 401 INVALID_CREDENTIALS")
        void wrongPasswordAndUnknownEmailLookTheSame() throws Exception {
            registerAndVerify(EMAIL, PASSWORD);

            login(EMAIL, "wrong-password")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));

            login("nobody@example.com", PASSWORD)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));
        }
    }

    // --------------------------------------------------------- обновление и ротация

    @Nested
    @DisplayName("POST /api/auth/refresh")
    class RefreshAndRotation {

        @Test
        @DisplayName("отдаёт новую пару и отзывает предъявленный токен")
        void rotatesRefreshToken() throws Exception {
            registerAndVerify(EMAIL, PASSWORD);
            String first = refreshTokenOf(login(EMAIL, PASSWORD).andReturn());

            String rotated = refresh(first)
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String second = JsonPath.read(rotated, "$.refreshToken");

            assertThat(second).isNotEqualTo(first);
            // Новый токен работает...
            mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken(rotated)))
                    .andExpect(status().isOk());
            // ...а в базе осталось два токена: старый отозван и знает про свою замену — именно
            // на это опирается детект повторного использования.
            assertThat(revokedFlags()).containsExactlyInAnyOrder(true, false);
            assertThat(countRotatedTokens()).isEqualTo(1);
        }

        @Test
        @DisplayName("повторное использование уже провёрнутого токена гасит всю цепочку")
        void reuseOfRotatedTokenRevokesEverything() throws Exception {
            registerAndVerify(EMAIL, PASSWORD);
            String first = refreshTokenOf(login(EMAIL, PASSWORD).andReturn());
            String second = refreshTokenOf(refresh(first).andExpect(status().isOk()).andReturn());

            // Приходят со старым токеном второй раз — либо утёк наш, либо это вор.
            refresh(first)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"));

            // Ключевая часть: страдает не только предъявленный токен. Живая цепочка, выданная
            // взамен, тоже перестаёт работать — иначе вор остался бы с доступом.
            refresh(second)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"));
            assertThat(revokedFlags()).containsOnly(true);

            // Пароль при этом цел: пользователь просто логинится заново.
            login(EMAIL, PASSWORD).andExpect(status().isOk());
        }

        @Test
        @DisplayName("незнакомый токен — 401, без 500 и без утечек")
        void rejectsUnknownToken() throws Exception {
            refresh("this-token-was-never-issued")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"));
        }

        @Test
        @DisplayName("просроченный токен не обновляется")
        void rejectsExpiredToken() throws Exception {
            registerAndVerify(EMAIL, PASSWORD);
            String token = refreshTokenOf(login(EMAIL, PASSWORD).andReturn());

            expireAll("refresh_tokens");

            refresh(token)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"));
        }

        @Test
        @DisplayName("слишком длинный токен отсекается валидацией, до БД не доходит")
        void rejectsOversizedToken() throws Exception {
            refresh("x".repeat(129))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        }
    }

    // ------------------------------------------------------------------------ выход

    @Nested
    @DisplayName("POST /api/auth/logout")
    class Logout {

        @Test
        @DisplayName("гасит предъявленный токен")
        void revokesTheToken() throws Exception {
            registerAndVerify(EMAIL, PASSWORD);
            String token = refreshTokenOf(login(EMAIL, PASSWORD).andReturn());

            logout(token).andExpect(status().isNoContent());

            refresh(token)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"));
        }

        @Test
        @DisplayName("не разлогинивает остальные устройства: у логаута нет replaced_by")
        void doesNotTouchOtherSessions() throws Exception {
            registerAndVerify(EMAIL, PASSWORD);
            String phone = refreshTokenOf(login(EMAIL, PASSWORD).andReturn());
            String laptop = refreshTokenOf(login(EMAIL, PASSWORD).andReturn());

            logout(phone).andExpect(status().isNoContent());
            // Отозванный логаутом токен не должен уходить в ветку детекта кражи: у него нет
            // replacedBy, продолжать нечего. Иначе гонка «логаут и параллельный refresh той же
            // вкладки» разлогинивала бы человека на всех устройствах разом.
            refresh(phone).andExpect(status().isUnauthorized());

            refresh(laptop).andExpect(status().isOk());
        }

        @Test
        @DisplayName("идемпотентен: повторный выход и незнакомый токен — те же 204")
        void isIdempotent() throws Exception {
            registerAndVerify(EMAIL, PASSWORD);
            String token = refreshTokenOf(login(EMAIL, PASSWORD).andReturn());

            logout(token).andExpect(status().isNoContent());
            logout(token).andExpect(status().isNoContent());
            logout("never-issued-token").andExpect(status().isNoContent());
        }
    }

    // ------------------------------------------------------------------ сброс пароля

    @Nested
    @DisplayName("POST /api/auth/forgot-password + /api/auth/reset-password")
    class PasswordReset {

        @Test
        @DisplayName("меняет пароль по ссылке из письма и выкидывает все живые сессии")
        void resetsPasswordAndRevokesSessions() throws Exception {
            registerAndVerify(EMAIL, PASSWORD);
            String sessionToken = refreshTokenOf(login(EMAIL, PASSWORD).andReturn());

            forgotPassword(EMAIL).andExpect(status().isOk());
            String resetToken = extractResetToken(awaitSingleEmail());

            resetPassword(resetToken, NEW_PASSWORD).andExpect(status().isOk());

            login(EMAIL, NEW_PASSWORD).andExpect(status().isOk());
            login(EMAIL, PASSWORD).andExpect(status().isUnauthorized());
            // Сброс пароля — штатная реакция на «кажется, меня взломали»: уже открытая чужая
            // сессия обязана умереть вместе со старым паролем.
            refresh(sessionToken).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("ссылка одноразовая, повторно не срабатывает")
        void resetTokenIsSingleUse() throws Exception {
            registerAndVerify(EMAIL, PASSWORD);

            forgotPassword(EMAIL).andExpect(status().isOk());
            String resetToken = extractResetToken(awaitSingleEmail());

            resetPassword(resetToken, NEW_PASSWORD).andExpect(status().isOk());
            resetPassword(resetToken, "yet-another-password")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
        }

        @Test
        @DisplayName("просроченная ссылка не меняет пароль")
        void rejectsExpiredResetToken() throws Exception {
            registerAndVerify(EMAIL, PASSWORD);

            forgotPassword(EMAIL).andExpect(status().isOk());
            String resetToken = extractResetToken(awaitSingleEmail());

            expireAll("password_reset_tokens");

            resetPassword(resetToken, NEW_PASSWORD)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
            login(EMAIL, PASSWORD).andExpect(status().isOk());
        }

        @Test
        @DisplayName("незнакомому адресу отвечает так же, но письма не шлёт")
        void neverRevealsWhetherTheAccountExists() throws Exception {
            forgotPassword("nobody@example.com").andExpect(status().isOk());
            assertNoEmailSent();
        }

        @Test
        @DisplayName("заодно подтверждает email: переход по ссылке доказывает владение ящиком")
        void resetAlsoVerifiesTheEmail() throws Exception {
            register(EMAIL, PASSWORD).andExpect(status().isCreated());
            awaitSingleEmail();
            clearMailbox();
            assertThat(emailVerifiedOf(EMAIL)).isFalse();

            forgotPassword(EMAIL).andExpect(status().isOk());
            resetPassword(extractResetToken(awaitSingleEmail()), NEW_PASSWORD).andExpect(status().isOk());

            assertThat(emailVerifiedOf(EMAIL)).isTrue();
            login(EMAIL, NEW_PASSWORD).andExpect(status().isOk());
        }
    }

    // -------------------------------------------------------------- регистр адреса

    /**
     * Ящик один, значит и аккаунт один — независимо от того, каким регистром человек набрал
     * свой адрес в этот раз (см. EmailNormalizer).
     * <p>
     * До нормализации {@code Ivan@Company.com} и {@code ivan@company.com} заводили две
     * отдельные учётные записи: уникальный индекс по {@code users.email} регистрозависим и
     * их не склеивал. Дальше начиналось интересное — вход «не тем регистром» отвечал
     * «неверный пароль» при верном пароле, ссылка сброса уходила в ту из двух записей,
     * которую человек не заводил, а приглашения в проект (они всегда хранились в нижнем
     * регистре) не находили уже зарегистрированного пользователя и выписывали ему
     * приглашение вместо членства.
     */
    @Nested
    @DisplayName("регистр почтового адреса")
    class EmailCase {

        private static final String MIXED_CASE = "Ivan.Petrov@Example.COM";
        private static final String LOWER_CASE = "ivan.petrov@example.com";

        @Test
        @DisplayName("адрес сохраняется в нижнем регистре")
        void storesEmailLowercased() throws Exception {
            register(MIXED_CASE, PASSWORD).andExpect(status().isCreated());
            awaitSingleEmail();

            assertThat(countUsers(LOWER_CASE)).isEqualTo(1);
            assertThat(countUsers(MIXED_CASE)).isZero();
        }

        @Test
        @DisplayName("повторная регистрация другим регистром не заводит второй аккаунт")
        void doesNotCreateASecondAccountForTheSameMailbox() throws Exception {
            register(MIXED_CASE, PASSWORD).andExpect(status().isCreated());
            awaitSingleEmail();
            clearMailbox();

            // Тот же ответ, что и на свободный адрес (1.10) — но письмо уходит другое,
            // «аккаунт уже существует», и второй строки в users не появляется. Никнейм свой:
            // адреса здесь различаются только регистром, и выведенный из адреса был бы тем же.
            register(LOWER_CASE, PASSWORD, "someone-else").andExpect(status().isCreated());
            awaitSingleEmail();

            assertThat(countUsers(LOWER_CASE)).isEqualTo(1);
        }

        @Test
        @DisplayName("вход работает любым регистром")
        void loginIsCaseInsensitive() throws Exception {
            registerAndVerify(MIXED_CASE, PASSWORD);

            login(LOWER_CASE, PASSWORD).andExpect(status().isOk());
            login(MIXED_CASE, PASSWORD).andExpect(status().isOk());
            login(LOWER_CASE.toUpperCase(java.util.Locale.ROOT), PASSWORD).andExpect(status().isOk());
        }

        @Test
        @DisplayName("сброс пароля находит аккаунт по адресу в другом регистре")
        void forgotPasswordIsCaseInsensitive() throws Exception {
            registerAndVerify(MIXED_CASE, PASSWORD);

            forgotPassword(LOWER_CASE.toUpperCase(java.util.Locale.ROOT)).andExpect(status().isOk());
            resetPassword(extractResetToken(awaitSingleEmail()), NEW_PASSWORD).andExpect(status().isOk());

            login(MIXED_CASE, NEW_PASSWORD).andExpect(status().isOk());
        }
    }

    // ----------------------------------------------------------- запросы к API

    /** Никнейм выводится из адреса (V28): он обязателен при регистрации и уникален. */
    private ResultActions register(String email, String password) throws Exception {
        return register(email, password, usernameFrom(email));
    }

    private ResultActions register(String email, String password, String username) throws Exception {
        return postJson("/api/auth/register", """
                {"email":"%s","username":"%s","password":"%s","lastName":"Иванов","firstName":"Иван","patronymic":"Иванович"}
                """.formatted(email, username, password));
    }

    private ResultActions verifyEmail(String token) throws Exception {
        return postJson("/api/auth/verify-email", """
                {"token":"%s"}""".formatted(token));
    }

    private ResultActions resendVerification(String email) throws Exception {
        return postJson("/api/auth/resend-verification", """
                {"email":"%s"}""".formatted(email));
    }

    private ResultActions login(String email, String password) throws Exception {
        return postJson("/api/auth/login", """
                {"email":"%s","password":"%s"}""".formatted(email, password));
    }

    private ResultActions refresh(String token) throws Exception {
        return postJson("/api/auth/refresh", """
                {"refreshToken":"%s"}""".formatted(token));
    }

    private ResultActions logout(String token) throws Exception {
        return postJson("/api/auth/logout", """
                {"refreshToken":"%s"}""".formatted(token));
    }

    private ResultActions forgotPassword(String email) throws Exception {
        return postJson("/api/auth/forgot-password", """
                {"email":"%s"}""".formatted(email));
    }

    private ResultActions resetPassword(String token, String newPassword) throws Exception {
        return postJson("/api/auth/reset-password", """
                {"token":"%s","newPassword":"%s"}""".formatted(token, newPassword));
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path).contentType(APPLICATION_JSON).content(body));
    }

    /**
     * Путь «с нуля до входа в систему»: зарегистрировать, взять токен из письма, подтвердить.
     * Ящик после этого чистится — иначе awaitSingleEmail в самом тесте нашёл бы там письмо
     * подтверждения вместо того, ради которого тест написан.
     */
    private void registerAndVerify(String email, String password) throws Exception {
        register(email, password).andExpect(status().isCreated());
        verifyEmail(extractVerificationToken(awaitSingleEmail())).andExpect(status().isOk());
        clearMailbox();
    }

    // ------------------------------------------------------------------- разбор ответов

    private static String accessToken(String responseBody) {
        return JsonPath.read(responseBody, "$.accessToken");
    }

    private static String refreshTokenOf(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.refreshToken");
    }

    private static String extractVerificationToken(MimeMessage email) throws Exception {
        return extractLinkToken(VERIFY_LINK, bodyOf(email), "verification");
    }

    private static String extractResetToken(MimeMessage email) throws Exception {
        return extractLinkToken(RESET_LINK, bodyOf(email), "password reset");
    }

    private static String extractLinkToken(Pattern pattern, String body, String what) {
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            throw new AssertionError("No " + what + " link found in the email body:\n" + body);
        }
        return matcher.group(1);
    }

    /** Текст письма с уже раскодированным transfer-encoding: письма кириллические. */
    private static String bodyOf(MimeMessage email) throws Exception {
        return email.getContent().toString();
    }

    // ------------------------------------------------------------------ состояние в БД

    /**
     * Сдвигает срок жизни всех токенов таблицы в прошлое. Единственный способ проверить ветку
     * «просрочено»: TTL здесь — сутки и час, ждать их в тесте не вариант.
     */
    private void expireAll(String tokenTable) {
        jdbcTemplate.update("UPDATE " + tokenTable + " SET expires_at = now() - interval '1 hour'");
    }

    private boolean emailVerifiedOf(String email) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT email_verified FROM users WHERE email = ?", Boolean.class, email));
    }

    private int countUsers(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
    }

    /** Флаги revoked всех refresh-токенов, лежащих сейчас в базе. */
    private List<Boolean> revokedFlags() {
        return jdbcTemplate.queryForList("SELECT revoked FROM refresh_tokens", Boolean.class);
    }

    /** Токены, которые были обменяны на новые: отозваны и знают про свою замену. */
    private int countRotatedTokens() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE revoked = true AND replaced_by IS NOT NULL",
                Integer.class);
    }
}
