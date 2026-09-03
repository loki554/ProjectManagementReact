package com.pmtracker.project_management_backend.project;

import com.jayway.jsonpath.JsonPath;
import com.pmtracker.project_management_backend.auth.JwtService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.UserRepository;
import com.pmtracker.project_management_backend.support.IntegrationTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Приглашение незарегистрированных (4.2) целиком: от «пригласить адрес, которого нет в
 * users» до «человек зарегистрировался и оказался в проекте».
 * <p>
 * Проверяется через HTTP и настоящую почту, как и auth-флоу: смысл фичи в том, что ссылка
 * из письма работает, а не в том, что сервис позвали. Единственные два места, куда тест
 * лезет мимо API, — {@code UPDATE ... expires_at} (дожидаться недели честно нельзя) и
 * чтение таблицы приглашений там, где надо убедиться, что строка исчезла.
 * <p>
 * Права (кому можно приглашать, смотреть список и отзывать) живут не здесь, а в
 * {@link ProjectPermissionMatrixTest}: там для этого есть таблица, и дублировать её
 * половиной значило бы завести второе место, которое придётся не забыть обновить.
 */
class ProjectInvitationIntegrationTest extends IntegrationTest {

    private static final String ADMIN_EMAIL = "inviter@example.com";
    private static final String INVITEE_EMAIL = "newcomer@example.com";
    private static final String PASSWORD = "correct-horse-battery";

    /** Токен приглашения — 32 случайных байта в Base64URL без паддинга. */
    private static final Pattern INVITE_LINK = Pattern.compile("/invite\\?token=([A-Za-z0-9_-]+)");

    private static final Pattern VERIFY_LINK =
            Pattern.compile("/verify-email\\?token=([0-9a-fA-F-]{36})");

    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private ProjectInvitationRepository invitationRepository;
    @Autowired private ProjectInvitationCleanupJob cleanupJob;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID projectId;
    private String adminToken;

    @BeforeEach
    void createProjectWithAdmin() {
        User admin = user(ADMIN_EMAIL);

        Project project = new Project();
        project.setName("Invitation project");
        project.setSlug("invitation-project");
        project.setCreatedBy(admin);
        projectRepository.save(project);

        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(admin);
        membership.setRole(ProjectRole.OWNER);
        projectMemberRepository.save(membership);

        projectId = project.getId();
        adminToken = "Bearer " + jwtService.generateAccessToken(admin);
    }

    // --------------------------------------------------------------- выписка приглашения

    @Nested
    @DisplayName("POST /api/projects/{id}/members")
    class Inviting {

        @Test
        @DisplayName("незарегистрированному шлёт письмо со ссылкой, а не 404")
        void invitingUnknownEmailCreatesInvitation() throws Exception {
            invite(INVITEE_EMAIL, "MEMBER")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("INVITATION_SENT"))
                    .andExpect(jsonPath("$.member").doesNotExist())
                    .andExpect(jsonPath("$.invitation.email").value(INVITEE_EMAIL))
                    .andExpect(jsonPath("$.invitation.role").value("MEMBER"))
                    .andExpect(jsonPath("$.invitation.expired").value(false));

            MimeMessage email = awaitSingleEmail();
            assertThat(email.getAllRecipients()[0].toString()).isEqualTo(INVITEE_EMAIL);
            assertThat(email.getSubject()).contains("Приглашение в проект «Invitation project»");
            assertThat(bodyOf(email)).contains("http://localhost:5173/invite?token=");
            // Токен есть только в письме: в БД лежит его хеш, в ответе API — вообще ничего.
            assertThat(invitationTokenOf(email)).isNotBlank();
            assertThat(countInvitations()).isEqualTo(1);
        }

        @Test
        @DisplayName("зарегистрированного добавляет сразу и письма не шлёт")
        void invitingRegisteredUserAddsMemberRightAway() throws Exception {
            User existing = user("already.here@example.com");

            invite(existing.getEmail(), "VIEWER")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("MEMBER_ADDED"))
                    .andExpect(jsonPath("$.invitation").doesNotExist())
                    .andExpect(jsonPath("$.member.email").value(existing.getEmail()))
                    .andExpect(jsonPath("$.member.role").value("VIEWER"));

            assertNoEmailSent();
            assertThat(countInvitations()).isZero();
            assertThat(projectMemberRepository.findByProjectIdAndUserId(projectId, existing.getId()))
                    .isPresent();
        }

        @Test
        @DisplayName("повторное приглашение не плодит строк и гасит прошлую ссылку")
        void reInvitingReplacesTheLink() throws Exception {
            invite(INVITEE_EMAIL, "MEMBER").andExpect(status().isCreated());
            String firstToken = invitationTokenOf(awaitSingleEmail());
            clearMailbox();

            invite(INVITEE_EMAIL, "ADMIN").andExpect(status().isCreated());
            String secondToken = invitationTokenOf(awaitSingleEmail());

            assertThat(secondToken).isNotEqualTo(firstToken);
            assertThat(countInvitations()).isEqualTo(1);
            // Старая ссылка обязана умереть: иначе каждое «пригласить ещё раз» оставляло бы
            // в чужом почтовом ящике ещё одну рабочую дверь в проект.
            preview(firstToken).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
            preview(secondToken).andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("ADMIN"));
        }

        @Test
        @DisplayName("уже приглашённый, но успевший зарегистрироваться, добавляется участником — приглашение исчезает")
        void invitingAgainAfterTheInviteeRegisteredAddsThemAndDropsTheInvitation() throws Exception {
            invite(INVITEE_EMAIL, "MEMBER").andExpect(status().isCreated());
            String token = invitationTokenOf(awaitSingleEmail());
            clearMailbox();

            User newcomer = user(INVITEE_EMAIL);

            invite(INVITEE_EMAIL, "MEMBER")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("MEMBER_ADDED"));

            assertThat(projectMemberRepository.findByProjectIdAndUserId(projectId, newcomer.getId()))
                    .isPresent();
            assertThat(countInvitations()).isZero();
            preview(token).andExpect(status().isBadRequest());
        }
    }

    // ------------------------------------------------------------------------- просмотр

    @Nested
    @DisplayName("GET /api/invitations/{token}")
    class Preview {

        @Test
        @DisplayName("открывается без входа и показывает проект, роль и пригласившего")
        void previewIsPublic() throws Exception {
            invite(INVITEE_EMAIL, "MEMBER").andExpect(status().isCreated());
            String token = invitationTokenOf(awaitSingleEmail());

            preview(token)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.projectName").value("Invitation project"))
                    .andExpect(jsonPath("$.email").value(INVITEE_EMAIL))
                    .andExpect(jsonPath("$.role").value("MEMBER"))
                    .andExpect(jsonPath("$.invitedByName").value("Тестов inviter"));
        }

        @Test
        @DisplayName("просроченное приглашение неотличимо от несуществующего")
        void expiredAndUnknownTokensLookTheSame() throws Exception {
            invite(INVITEE_EMAIL, "MEMBER").andExpect(status().isCreated());
            String token = invitationTokenOf(awaitSingleEmail());
            expireAllInvitations();

            preview(token).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
            preview("definitely-not-a-real-token").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
        }

        @Test
        @DisplayName("отозванное приглашение перестаёт работать")
        void revokedInvitationStopsWorking() throws Exception {
            String invitationId = JsonPath.read(
                    invite(INVITEE_EMAIL, "MEMBER").andReturn().getResponse().getContentAsString(),
                    "$.invitation.id");
            String token = invitationTokenOf(awaitSingleEmail());

            mockMvc.perform(delete("/api/projects/" + projectId + "/invitations/" + invitationId)
                            .header(AUTHORIZATION, adminToken))
                    .andExpect(status().isNoContent());

            preview(token).andExpect(status().isBadRequest());
            assertThat(countInvitations()).isZero();
        }

        @Test
        @DisplayName("приглашение чужого проекта нельзя отозвать, зная только его id")
        void revokingAcrossProjectsIsNotFound() throws Exception {
            String invitationId = JsonPath.read(
                    invite(INVITEE_EMAIL, "MEMBER").andReturn().getResponse().getContentAsString(),
                    "$.invitation.id");
            awaitSingleEmail();

            UUID otherProjectId = otherProjectOwnedByAdmin();

            mockMvc.perform(delete("/api/projects/" + otherProjectId + "/invitations/" + invitationId)
                            .header(AUTHORIZATION, adminToken))
                    .andExpect(status().isNotFound());
            assertThat(countInvitations()).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------------ принятие

    @Nested
    @DisplayName("POST /api/invitations/{token}/accept")
    class Accepting {

        @Test
        @DisplayName("вошедший под тем же адресом становится участником, ссылка сгорает")
        void acceptAddsTheMemberAndBurnsTheToken() throws Exception {
            invite(INVITEE_EMAIL, "ADMIN").andExpect(status().isCreated());
            String token = invitationTokenOf(awaitSingleEmail());
            clearMailbox();

            // Аккаунт появился уже после отправки письма — ровно тот случай, ради которого
            // существует ручное принятие: автоматика на подтверждении адреса тут не сработает,
            // потому что адрес был подтверждён раньше.
            User newcomer = user(INVITEE_EMAIL);

            accept(token, bearer(newcomer))
                    .andExpect(status().isOk())
                    // В ответе — куда именно человек попал: id и slug проекта он до этого
                    // момента нигде не видел, а страница приглашения должна увести его туда.
                    .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                    .andExpect(jsonPath("$.projectSlug").value("invitation-project"))
                    .andExpect(jsonPath("$.projectName").value("Invitation project"))
                    .andExpect(jsonPath("$.role").value("ADMIN"));

            assertThat(projectMemberRepository.findByProjectIdAndUserId(projectId, newcomer.getId()))
                    .isPresent();
            assertThat(countInvitations()).isZero();
            accept(token, bearer(newcomer)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
        }

        @Test
        @DisplayName("вошедший под другим адресом получает 403, а не доступ к проекту")
        void forwardedLinkDoesNotWorkForSomeoneElse() throws Exception {
            invite(INVITEE_EMAIL, "MEMBER").andExpect(status().isCreated());
            String token = invitationTokenOf(awaitSingleEmail());

            User stranger = user("stranger@example.com");

            accept(token, bearer(stranger))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("INVITATION_EMAIL_MISMATCH"));

            assertThat(projectMemberRepository.findByProjectIdAndUserId(projectId, stranger.getId()))
                    .isEmpty();
            assertThat(countInvitations()).isEqualTo(1);
        }

        @Test
        @DisplayName("без входа принять нельзя")
        void acceptRequiresAuthentication() throws Exception {
            invite(INVITEE_EMAIL, "MEMBER").andExpect(status().isCreated());
            String token = invitationTokenOf(awaitSingleEmail());

            mockMvc.perform(post("/api/invitations/" + token + "/accept"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("уже участнику проекта отдаёт его строку, а не 409")
        void acceptingWhenAlreadyAMemberIsNotAnError() throws Exception {
            invite(INVITEE_EMAIL, "MEMBER").andExpect(status().isCreated());
            String token = invitationTokenOf(awaitSingleEmail());

            User newcomer = user(INVITEE_EMAIL);
            ProjectMember existing = new ProjectMember();
            existing.setProject(projectRepository.findById(projectId).orElseThrow());
            existing.setUser(newcomer);
            existing.setRole(ProjectRole.VIEWER);
            projectMemberRepository.save(existing);

            accept(token, bearer(newcomer))
                    .andExpect(status().isOk())
                    // Роль остаётся та, что уже была: приглашение не повышает и не понижает
                    // существующего участника.
                    .andExpect(jsonPath("$.role").value("VIEWER"));
            assertThat(countInvitations()).isZero();
        }
    }

    // ------------------------------------------- регистрация по приглашению (главный путь)

    @Nested
    @DisplayName("Регистрация по приглашению")
    class RegistrationByInvitation {

        @Test
        @DisplayName("подтвердив адрес, приглашённый оказывается в проекте без единого клика")
        void verifyingEmailAcceptsPendingInvitations() throws Exception {
            invite(INVITEE_EMAIL, "MEMBER").andExpect(status().isCreated());
            awaitSingleEmail();
            clearMailbox();

            register(INVITEE_EMAIL).andExpect(status().isCreated());
            String verificationToken = verificationTokenOf(awaitSingleEmail());

            // До подтверждения адреса — ещё никакого доступа: письмо могли переслать.
            assertThat(membersOf(projectId)).doesNotContain(INVITEE_EMAIL);

            verifyEmail(verificationToken).andExpect(status().isOk());

            assertThat(membersOf(projectId)).contains(INVITEE_EMAIL);
            assertThat(countInvitations()).isZero();
        }

        @Test
        @DisplayName("регистр адреса не мешает: пригласили Ivan@, зарегистрировался ivan@")
        void emailCaseDoesNotBreakTheMatch() throws Exception {
            invite("Ivan@Example.COM", "MEMBER").andExpect(status().isCreated());
            awaitSingleEmail();
            clearMailbox();

            register("ivan@example.com").andExpect(status().isCreated());
            verifyEmail(verificationTokenOf(awaitSingleEmail())).andExpect(status().isOk());

            assertThat(membersOf(projectId)).contains("ivan@example.com");
        }

        @Test
        @DisplayName("просроченное приглашение подтверждением адреса не воскрешается")
        void expiredInvitationIsNotAcceptedOnVerification() throws Exception {
            invite(INVITEE_EMAIL, "MEMBER").andExpect(status().isCreated());
            awaitSingleEmail();
            clearMailbox();
            expireAllInvitations();

            register(INVITEE_EMAIL).andExpect(status().isCreated());
            verifyEmail(verificationTokenOf(awaitSingleEmail())).andExpect(status().isOk());

            assertThat(membersOf(projectId)).doesNotContain(INVITEE_EMAIL);
            // Строка осталась: убирать просроченное — дело ночного джоба, а не верификации.
            assertThat(countInvitations()).isEqualTo(1);
        }

        @Test
        @DisplayName("одно подтверждение принимает приглашения во все проекты сразу")
        void allPendingInvitationsAreAcceptedAtOnce() throws Exception {
            UUID secondProjectId = otherProjectOwnedByAdmin();

            invite(projectId, INVITEE_EMAIL, "MEMBER").andExpect(status().isCreated());
            invite(secondProjectId, INVITEE_EMAIL, "VIEWER").andExpect(status().isCreated());
            awaitEmails(2);
            clearMailbox();

            register(INVITEE_EMAIL).andExpect(status().isCreated());
            verifyEmail(verificationTokenOf(awaitSingleEmail())).andExpect(status().isOk());

            assertThat(membersOf(projectId)).contains(INVITEE_EMAIL);
            assertThat(membersOf(secondProjectId)).contains(INVITEE_EMAIL);
            assertThat(countInvitations()).isZero();
        }
    }

    // -------------------------------------------------------------------------- уборка

    @Test
    @DisplayName("ночная уборка сносит просроченные приглашения и не трогает живые")
    void cleanupDeletesOnlyExpiredInvitations() throws Exception {
        invite(INVITEE_EMAIL, "MEMBER").andExpect(status().isCreated());
        awaitSingleEmail();
        clearMailbox();
        expireAllInvitations();

        invite("still.valid@example.com", "MEMBER").andExpect(status().isCreated());
        awaitSingleEmail();

        cleanupJob.deleteExpiredInvitations();

        assertThat(countInvitations()).isEqualTo(1);
        assertThat(invitationRepository.findAll().getFirst().getEmail()).isEqualTo("still.valid@example.com");
    }

    // ------------------------------------------------------------------- вспомогательное

    private ResultActions invite(String email, String role) throws Exception {
        return invite(projectId, email, role);
    }

    private ResultActions invite(UUID targetProjectId, String email, String role) throws Exception {
        return mockMvc.perform(post("/api/projects/" + targetProjectId + "/members")
                .header(AUTHORIZATION, adminToken)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"email":"%s","role":"%s"}""".formatted(email, role)));
    }

    private ResultActions preview(String token) throws Exception {
        return mockMvc.perform(get("/api/invitations/" + token));
    }

    private ResultActions accept(String token, String bearer) throws Exception {
        return mockMvc.perform(post("/api/invitations/" + token + "/accept").header(AUTHORIZATION, bearer));
    }

    private ResultActions register(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s","lastName":"Новиков","firstName":"Пётр"}"""
                        .formatted(email, PASSWORD)));
    }

    private ResultActions verifyEmail(String token) throws Exception {
        return mockMvc.perform(post("/api/auth/verify-email")
                .contentType(APPLICATION_JSON)
                .content("""
                        {"token":"%s"}""".formatted(token)));
    }

    private List<String> membersOf(UUID targetProjectId) {
        return projectMemberRepository.findByProjectIdWithUser(targetProjectId).stream()
                .map(member -> member.getUser().getEmail())
                .toList();
    }

    private UUID otherProjectOwnedByAdmin() {
        User admin = userRepository.findByEmail(ADMIN_EMAIL).orElseThrow();
        Project other = new Project();
        other.setName("Second project");
        other.setSlug("second-project");
        other.setCreatedBy(admin);
        projectRepository.save(other);

        ProjectMember membership = new ProjectMember();
        membership.setProject(other);
        membership.setUser(admin);
        membership.setRole(ProjectRole.OWNER);
        projectMemberRepository.save(membership);
        return other.getId();
    }

    private User user(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("$2a$10$notusedbythistestnotusedbythistestnotusedbythistestno");
        user.setLastName("Тестов");
        user.setFirstName(email.substring(0, email.indexOf('@')));
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateAccessToken(user);
    }

    private long countInvitations() {
        return invitationRepository.count();
    }

    /**
     * Дождаться недели до истечения приглашения честно нельзя, а ветка «просрочено» слишком
     * важна, чтобы остаться непроверенной, — тот же приём, что в AuthFlowIntegrationTest.
     */
    private void expireAllInvitations() {
        jdbcTemplate.update("UPDATE project_invitations SET expires_at = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(1))));
    }

    private static String invitationTokenOf(MimeMessage email) throws Exception {
        return extractLinkToken(INVITE_LINK, bodyOf(email));
    }

    private static String verificationTokenOf(MimeMessage email) throws Exception {
        return extractLinkToken(VERIFY_LINK, bodyOf(email));
    }

    private static String extractLinkToken(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            throw new AssertionError("No link matching " + pattern + " in the email body:\n" + body);
        }
        return matcher.group(1);
    }

    private static String bodyOf(MimeMessage email) throws Exception {
        return email.getContent().toString();
    }
}
