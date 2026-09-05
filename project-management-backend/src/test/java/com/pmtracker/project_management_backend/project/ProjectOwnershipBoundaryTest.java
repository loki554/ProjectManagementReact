package com.pmtracker.project_management_backend.project;

import com.pmtracker.project_management_backend.auth.JwtService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.UserRepository;
import com.pmtracker.project_management_backend.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Граница владения проектом: раздавать и отбирать роль OWNER может только OWNER.
 * <p>
 * Отдельный класс, а не строки в {@link ProjectPermissionMatrixTest}: та таблица проверяет
 * «какая роль вызывающего пускает в эндпоинт», и цель у всех её строк про участников — VIEWER.
 * Здесь же вопрос другой — не кто зовёт, а кого трогают, — и ответ на него от роли цели
 * зависит сильнее, чем от роли вызывающего. Втиснуть это в матрицу значило бы завести в ней
 * второе измерение ради трёх строк.
 * <p>
 * Дыра, ради которой класс написан, выглядела так: ADMIN повышал себя до OWNER, после чего
 * владельцев становилось двое и guard «должен остаться хотя бы один владелец» замолкал —
 * дальше настоящий владелец понижался до VIEWER и терял собственный проект. Поэтому в
 * фикстуре сразу два владельца: с одним любая проверка прошла бы «зелено» просто потому, что
 * её раньше перехватил бы CANNOT_REMOVE_LAST_OWNER, и настоящая проверка роли осталась бы
 * непокрытой.
 */
class ProjectOwnershipBoundaryTest extends IntegrationTest {

    private static final String UNUSED_PASSWORD_HASH = "$2a$10$fixture.hash.never.verified.by.these.tests......";

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;

    private UUID projectId;
    private UUID ownerId;
    private UUID secondOwnerId;
    private UUID adminId;
    private UUID memberId;
    private String ownerToken;
    private String adminToken;

    @BeforeEach
    void createFixture() {
        User owner = user("owner@example.com");
        User secondOwner = user("second.owner@example.com");
        User admin = user("admin@example.com");
        User member = user("member@example.com");

        Project project = new Project();
        project.setName("Ownership boundary project");
        project.setSlug("ownership-boundary-project");
        project.setCreatedBy(owner);
        projectRepository.save(project);

        membership(project, owner, ProjectRole.OWNER);
        membership(project, secondOwner, ProjectRole.OWNER);
        membership(project, admin, ProjectRole.ADMIN);
        membership(project, member, ProjectRole.MEMBER);

        projectId = project.getId();
        ownerId = owner.getId();
        secondOwnerId = secondOwner.getId();
        adminId = admin.getId();
        memberId = member.getId();
        ownerToken = bearer(owner);
        adminToken = bearer(admin);
    }

    // ---------------------------------------------------------------- чего нельзя админу

    @Test
    @DisplayName("ADMIN не может повысить себя до OWNER")
    void adminCannotPromoteSelfToOwner() throws Exception {
        mockMvc.perform(patch("/api/projects/" + projectId + "/members/" + adminId)
                        .header(AUTHORIZATION, adminToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"role":"OWNER"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));

        assertThat(roleOf(adminId)).isEqualTo(ProjectRole.ADMIN);
    }

    @Test
    @DisplayName("ADMIN не может выдать роль OWNER кому-то ещё")
    void adminCannotGrantOwnerToSomeoneElse() throws Exception {
        mockMvc.perform(patch("/api/projects/" + projectId + "/members/" + memberId)
                        .header(AUTHORIZATION, adminToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"role":"OWNER"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));

        assertThat(roleOf(memberId)).isEqualTo(ProjectRole.MEMBER);
    }

    /**
     * Ключевой случай. Владельцев двое, то есть «последний владелец» не срабатывает и
     * единственное, что стоит между админом и чужим проектом, — проверка роли.
     */
    @Test
    @DisplayName("ADMIN не может понизить OWNER, даже когда владельцев несколько")
    void adminCannotDemoteOwnerEvenWhenAnotherOwnerRemains() throws Exception {
        mockMvc.perform(patch("/api/projects/" + projectId + "/members/" + secondOwnerId)
                        .header(AUTHORIZATION, adminToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"role":"MEMBER"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));

        assertThat(roleOf(secondOwnerId)).isEqualTo(ProjectRole.OWNER);
    }

    @Test
    @DisplayName("ADMIN не может исключить OWNER из проекта")
    void adminCannotRemoveOwner() throws Exception {
        mockMvc.perform(delete("/api/projects/" + projectId + "/members/" + secondOwnerId)
                        .header(AUTHORIZATION, adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));

        assertThat(roleOf(secondOwnerId)).isEqualTo(ProjectRole.OWNER);
    }

    @Test
    @DisplayName("ADMIN не может пригласить нового участника сразу владельцем")
    void adminCannotInviteAsOwner() throws Exception {
        mockMvc.perform(post("/api/projects/" + projectId + "/members")
                        .header(AUTHORIZATION, adminToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"newcomer@example.com","role":"OWNER"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));
    }

    // ------------------------------------------------------------ что владельцу по-прежнему можно

    @Test
    @DisplayName("OWNER выдаёт и отбирает владение")
    void ownerManagesOwnership() throws Exception {
        mockMvc.perform(patch("/api/projects/" + projectId + "/members/" + memberId)
                        .header(AUTHORIZATION, ownerToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"role":"OWNER"}"""))
                .andExpect(status().isOk());
        assertThat(roleOf(memberId)).isEqualTo(ProjectRole.OWNER);

        mockMvc.perform(patch("/api/projects/" + projectId + "/members/" + secondOwnerId)
                        .header(AUTHORIZATION, ownerToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"role":"MEMBER"}"""))
                .andExpect(status().isOk());
        assertThat(roleOf(secondOwnerId)).isEqualTo(ProjectRole.MEMBER);
    }

    @Test
    @DisplayName("ADMIN по-прежнему управляет ролями, которые не касаются владения")
    void adminStillManagesNonOwnerRoles() throws Exception {
        mockMvc.perform(patch("/api/projects/" + projectId + "/members/" + memberId)
                        .header(AUTHORIZATION, adminToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"role":"VIEWER"}"""))
                .andExpect(status().isOk());
        assertThat(roleOf(memberId)).isEqualTo(ProjectRole.VIEWER);

        mockMvc.perform(delete("/api/projects/" + projectId + "/members/" + memberId)
                        .header(AUTHORIZATION, adminToken))
                .andExpect(status().isNoContent());
    }

    /**
     * Проверка роли не отменяет прежнюю: последнего владельца по-прежнему нельзя убрать —
     * даже владельцу и даже самому себе, иначе проект остался бы без хозяина.
     */
    @Test
    @DisplayName("последнего владельца нельзя понизить и владельцу")
    void lastOwnerStillProtected() throws Exception {
        mockMvc.perform(patch("/api/projects/" + projectId + "/members/" + secondOwnerId)
                        .header(AUTHORIZATION, ownerToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"role":"MEMBER"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/projects/" + projectId + "/members/" + ownerId)
                        .header(AUTHORIZATION, ownerToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"role":"MEMBER"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CANNOT_REMOVE_LAST_OWNER"));

        assertThat(roleOf(ownerId)).isEqualTo(ProjectRole.OWNER);
    }

    // ------------------------------------------------------------------------- helpers

    private ProjectRole roleOf(UUID userId) {
        return projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new AssertionError("Membership disappeared for user " + userId))
                .getRole();
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateAccessToken(user);
    }

    private User user(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(UNUSED_PASSWORD_HASH);
        user.setLastName("Тестов");
        user.setFirstName(email.substring(0, email.indexOf('@')));
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private void membership(Project project, User user, ProjectRole role) {
        ProjectMember member = new ProjectMember();
        member.setProject(project);
        member.setUser(user);
        member.setRole(role);
        projectMemberRepository.save(member);
    }
}
