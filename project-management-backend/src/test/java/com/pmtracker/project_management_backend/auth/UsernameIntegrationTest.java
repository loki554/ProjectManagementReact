package com.pmtracker.project_management_backend.auth;

import com.pmtracker.project_management_backend.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Никнейм пользователя (V28): формат, уникальность, смена в профиле.
 * <p>
 * Самое важное здесь — <b>порядок двух проверок в регистрации</b>, и ошибка в нём выглядит
 * как работающая функция. Занятая почта отвечает успехом намеренно (владельцу ящика уходит
 * письмо, а спрашивающему не сообщается ничего — см. {@code AuthService.register}), занятый
 * никнейм отвечает 409 тоже намеренно: без этого форму нельзя заполнить. Но если проверить
 * их в обратном порядке, разница между двумя ответами становится ровно тем индикатором
 * существования аккаунта, который в регистрации так старательно убран: занятый никнейм при
 * свободном адресе дал бы 409, а тот же никнейм при занятом адресе — успех. Ни один тест
 * про сам никнейм такую перестановку не поймает, поэтому она проверяется отдельно и явно.
 * <p>
 * Второе — формат. На нём держится разбор @упоминаний: никнейм с пробелом или точкой
 * означал бы человека, которого нельзя позвать, и при этом ни одной ошибки нигде.
 */
class UsernameIntegrationTest extends IntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------ регистрация

    @Nested
    @DisplayName("регистрация")
    class Registration {

        @Test
        @DisplayName("никнейм сохраняется и приходит обратно в профиле")
        void theUsernameIsStoredAndReturned() throws Exception {
            register("ivan@example.com", "ivanov").andExpect(status().isCreated());

            assertThat(usernameOf("ivan@example.com")).isEqualTo("ivanov");
        }

        @Test
        @DisplayName("занятый никнейм — 409 USERNAME_TAKEN, а не молчание")
        void aTakenUsernameIsRejected() throws Exception {
            register("first@example.com", "ivanov").andExpect(status().isCreated());

            register("second@example.com", "ivanov")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("USERNAME_TAKEN"));

            assertThat(userRepository.existsByEmail("second@example.com")).isFalse();
        }

        /**
         * Сердце класса, см. комментарий к нему. Регистрация на занятый адрес отвечает
         * одинаково независимо от того, свободен никнейм или нет, — иначе по этой паре
         * ответов можно было бы перебором узнавать, у кого здесь есть аккаунт.
         */
        @Test
        @DisplayName("занятый адрес отвечает одинаково при любом никнейме")
        void aTakenEmailLooksTheSameWhicheverUsernameIsUsed() throws Exception {
            register("taken@example.com", "ivanov").andExpect(status().isCreated());
            clearMailbox();

            // Свободный никнейм на занятый адрес — успех (и письмо владельцу ящика).
            register("taken@example.com", "petrov").andExpect(status().isCreated());
            // Занятый никнейм на тот же занятый адрес — 409 про никнейм, и это НЕ должно
            // отличать «адрес занят» от «адрес свободен»: тот же 409 придёт и на свободный
            // адрес ниже.
            register("taken@example.com", "ivanov").andExpect(status().isConflict());
            register("brand-new@example.com", "ivanov").andExpect(status().isConflict());

            // Второй аккаунт на занятый адрес так и не появился.
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM users WHERE email = ?", Integer.class, "taken@example.com"))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("регистр приводится к нижнему, а не отвергается")
        void mixedCaseIsNormalisedRatherThanRejected() throws Exception {
            register("ivan@example.com", "Ivanov").andExpect(status().isCreated());

            assertThat(usernameOf("ivan@example.com")).isEqualTo("ivanov");
        }

        @Test
        @DisplayName("никнейм, отличающийся только регистром, считается занятым")
        void caseDoesNotMakeUsernamesDifferent() throws Exception {
            register("first@example.com", "ivanov").andExpect(status().isCreated());

            register("second@example.com", "IVANOV")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("USERNAME_TAKEN"));
        }

        /**
         * Пробел, точка, кириллица, {@code @} — всё это делает никнейм неразбираемым в тексте
         * комментария, то есть человека, которого нельзя позвать. Отсюда и жёсткий набор.
         */
        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"ив", "иванов", "ivan ov", "ivan.ov", "ivan@ov", "ivan/ov",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
        @DisplayName("никнейм не по формату — 400")
        void malformedUsernamesAreRejected(String username) throws Exception {
            register("someone@example.com", username)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("никнейм обязателен")
        void theUsernameIsRequired() throws Exception {
            postRegister("""
                    {"email":"someone@example.com","password":"%s","lastName":"И","firstName":"И"}"""
                    .formatted(PASSWORD))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        }
    }

    // ---------------------------------------------------------------- смена в профиле

    @Nested
    @DisplayName("смена в профиле")
    class Rename {

        @Test
        @DisplayName("никнейм меняется вместе с остальным профилем")
        void theUsernameCanBeChanged() throws Exception {
            String auth = registerAndSignIn("ivan@example.com", "ivanov");

            updateProfile(auth, "ivan-the-second")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("ivan-the-second"));

            assertThat(usernameOf("ivan@example.com")).isEqualTo("ivan-the-second");
        }

        @Test
        @DisplayName("занятый чужой никнейм — 409, профиль не меняется")
        void takingSomeoneElsesUsernameIsRejected() throws Exception {
            register("other@example.com", "petrov").andExpect(status().isCreated());
            String auth = registerAndSignIn("ivan@example.com", "ivanov");

            updateProfile(auth, "petrov")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("USERNAME_TAKEN"));

            assertThat(usernameOf("ivan@example.com")).isEqualTo("ivanov");
        }

        /**
         * Сохранение профиля без единой правки не должно спотыкаться о собственный же
         * никнейм человека — отсюда {@code existsByUsernameAndIdNot}, а не
         * {@code existsByUsername}. Забыть здесь «AndIdNot» ничего не стоит, а последствие
         * — профиль, который вообще нельзя сохранить.
         */
        @Test
        @DisplayName("сохранение своего же никнейма не считается конфликтом")
        void keepingYourOwnUsernameIsNotAConflict() throws Exception {
            String auth = registerAndSignIn("ivan@example.com", "ivanov");

            updateProfile(auth, "ivanov")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("ivanov"));
        }

        @Test
        @DisplayName("регистр при смене тоже приводится к нижнему")
        void renameNormalisesCaseToo() throws Exception {
            String auth = registerAndSignIn("ivan@example.com", "ivanov");

            updateProfile(auth, "Ivan-Petrov").andExpect(jsonPath("$.username").value("ivan-petrov"));
        }

        @Test
        @DisplayName("никнейм не по формату не проходит и через профиль")
        void malformedUsernamesAreRejectedOnRenameToo() throws Exception {
            String auth = registerAndSignIn("ivan@example.com", "ivanov");

            updateProfile(auth, "иванов")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

            assertThat(usernameOf("ivan@example.com")).isEqualTo("ivanov");
        }

        @Test
        @DisplayName("никнейм виден в /users/me")
        void theProfileEndpointReturnsTheUsername() throws Exception {
            String auth = registerAndSignIn("ivan@example.com", "ivanov");

            mockMvc.perform(get("/api/users/me").header(AUTHORIZATION, auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("ivanov"));
        }
    }

    // ------------------------------------------------------------------- инструменты

    private ResultActions register(String email, String username) throws Exception {
        return postRegister("""
                {"email":"%s","username":"%s","password":"%s","lastName":"Иванов","firstName":"Иван"}"""
                .formatted(email, username, PASSWORD));
    }

    private ResultActions postRegister(String body) throws Exception {
        return mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body));
    }

    /**
     * Регистрация плюс токен, минуя подтверждение почты: тесты здесь не про верификацию
     * (её проходит AuthFlowIntegrationTest), а вход неподтверждённого отвечал бы 403.
     */
    private String registerAndSignIn(String email, String username) throws Exception {
        register(email, username).andExpect(status().isCreated());
        jdbcTemplate.update("UPDATE users SET email_verified = true WHERE email = ?", email);
        return "Bearer " + jwtService.generateAccessToken(userRepository.findByEmail(email).orElseThrow());
    }

    private ResultActions updateProfile(String auth, String username) throws Exception {
        return mockMvc.perform(patch("/api/users/me")
                .header(AUTHORIZATION, auth)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"username":"%s","lastName":"Иванов","firstName":"Иван"}""".formatted(username)));
    }

    private String usernameOf(String email) {
        return jdbcTemplate.queryForObject("SELECT username FROM users WHERE email = ?", String.class, email);
    }
}
