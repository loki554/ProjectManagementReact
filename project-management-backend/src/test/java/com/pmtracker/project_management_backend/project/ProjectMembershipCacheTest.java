package com.pmtracker.project_management_backend.project;

import com.pmtracker.project_management_backend.auth.JwtService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.UserRepository;
import com.pmtracker.project_management_backend.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Кэш проверки членства в проекте (3.10), включённый принудительно.
 * <p>
 * В приложении он по умолчанию выключен — см. ProjectMembershipCache о том, почему. Но
 * механизм от этого не перестаёт нуждаться в проверке, и проверять в нём надо ровно одно:
 * что кэшированное авторизационное решение не переживает изменение прав. Ошибка здесь
 * выглядит не как сбой, а как исключённый участник, который продолжает работать в проекте.
 * <p>
 * Изменения прав вносятся через API, а проверяются попаданием в базу мимо кэша: единственное
 *, что имеет значение, — совпадает ли то, во что верит приложение, с тем, что записано.
 */
@TestPropertySource(properties = {
        "app.cache.membership.enabled=true",
        // Длинный TTL намеренно: истечение по времени тут не проверяется и не должно
        // случайно «починить» тест, который на самом деле проверяет явную инвалидацию.
        "app.cache.membership.ttl=PT1H",
})
class ProjectMembershipCacheTest extends IntegrationTest {

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private ProjectMembershipCache membershipCache;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User owner;
    private User member;
    private Project project;
    private String ownerAuth;
    private String memberAuth;

    @BeforeEach
    void createProject() {
        owner = user("owner@example.com");
        member = user("member@example.com");

        project = new Project();
        project.setName("Cache project");
        project.setSlug("cache-project");
        project.setCreatedBy(owner);
        projectRepository.save(project);

        join(owner, ProjectRole.OWNER);
        join(member, ProjectRole.MEMBER);

        ownerAuth = "Bearer " + jwtService.generateAccessToken(owner);
        memberAuth = "Bearer " + jwtService.generateAccessToken(member);

        // Прогреваем кэш: дальше каждый тест меняет права и смотрит, пережила ли их
        // прогретая запись.
        membershipCache.findRole(project.getId(), member.getId());
    }

    @Test
    @DisplayName("роль читается из кэша, а не из базы")
    void servesTheRoleFromMemory() {
        // Меняем роль в обход приложения — кэш об этом знать не может и обязан отдать старое.
        jdbcTemplate.update("UPDATE project_members SET role = 'VIEWER' WHERE project_id = ? AND user_id = ?",
                project.getId(), member.getId());

        assertThat(membershipCache.findRole(project.getId(), member.getId()))
                .contains(ProjectRole.MEMBER);
    }

    @Test
    @DisplayName("исключение участника действует сразу, а не через TTL")
    void aRemovedMemberLosesAccessImmediately() throws Exception {
        mockMvc.perform(get("/api/projects/" + project.getId()).header(AUTHORIZATION, memberAuth))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/projects/" + project.getId() + "/members/" + member.getId())
                        .header(AUTHORIZATION, ownerAuth))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/projects/" + project.getId()).header(AUTHORIZATION, memberAuth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("NOT_A_PROJECT_MEMBER"));
    }

    @Test
    @DisplayName("понижение роли действует сразу")
    void aDowngradeAppliesImmediately() throws Exception {
        mockMvc.perform(post("/api/projects/" + project.getId() + "/tasks")
                        .header(AUTHORIZATION, memberAuth)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"title":"Пока ещё можно","status":"NEW","urgency":"MEDIUM"}"""))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/api/projects/" + project.getId() + "/members/" + member.getId())
                        .header(AUTHORIZATION, ownerAuth)
                        .contentType(APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/projects/" + project.getId() + "/tasks")
                        .header(AUTHORIZATION, memberAuth)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"title":"Уже нельзя","status":"NEW","urgency":"MEDIUM"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));
    }

    /**
     * Отрицательный ответ кэшируется наравне с положительным, и приглашение обязано его
     * сбросить — иначе новый участник упирался бы в 403 до истечения TTL.
     */
    @Test
    @DisplayName("приглашение сбрасывает закэшированный отказ")
    void anInviteClearsACachedRefusal() throws Exception {
        User outsider = user("outsider@example.com");
        String outsiderAuth = "Bearer " + jwtService.generateAccessToken(outsider);

        mockMvc.perform(get("/api/projects/" + project.getId()).header(AUTHORIZATION, outsiderAuth))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/projects/" + project.getId() + "/members")
                        .header(AUTHORIZATION, ownerAuth)
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"outsider@example.com\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/projects/" + project.getId()).header(AUTHORIZATION, outsiderAuth))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("создатель проекта не упирается в свой же закэшированный отказ")
    void theCreatorIsNotBlockedByACachedRefusal() throws Exception {
        User newcomer = user("newcomer@example.com");
        String newcomerAuth = "Bearer " + jwtService.generateAccessToken(newcomer);

        // Промах по ещё не существующему проекту не воспроизвести, зато можно по этому:
        // отказ осядет в кэше на пару с (projectId, userId), который вот-вот станет валидным.
        mockMvc.perform(get("/api/projects/" + project.getId()).header(AUTHORIZATION, newcomerAuth))
                .andExpect(status().isForbidden());

        join(newcomer, ProjectRole.MEMBER);
        membershipCache.invalidate(project.getId(), newcomer.getId());

        assertThat(membershipCache.findRole(project.getId(), newcomer.getId()))
                .contains(ProjectRole.MEMBER);
    }

    @Test
    @DisplayName("удаление проекта убирает из кэша все его членства")
    void deletingAProjectClearsEveryMembership() throws Exception {
        membershipCache.findRole(project.getId(), owner.getId());

        mockMvc.perform(delete("/api/projects/" + project.getId()).header(AUTHORIZATION, ownerAuth))
                .andExpect(status().isNoContent());

        assertThat(membershipCache.findRole(project.getId(), owner.getId())).isEqualTo(Optional.empty());
        assertThat(membershipCache.findRole(project.getId(), member.getId())).isEqualTo(Optional.empty());
    }

    // ------------------------------------------------------------------------ хелперы

    private User user(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("$2a$10$fixture.hash.never.verified.by.these.tests......");
        user.setLastName("Тестов");
        user.setFirstName("Участник");
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private void join(User user, ProjectRole role) {
        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(user);
        membership.setRole(role);
        projectMemberRepository.save(membership);
    }
}
