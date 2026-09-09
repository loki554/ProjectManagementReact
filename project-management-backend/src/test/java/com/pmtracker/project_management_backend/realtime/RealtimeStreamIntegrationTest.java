package com.pmtracker.project_management_backend.realtime;

import com.pmtracker.project_management_backend.auth.JwtService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.UserRepository;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectMember;
import com.pmtracker.project_management_backend.project.ProjectMemberRepository;
import com.pmtracker.project_management_backend.project.ProjectRepository;
import com.pmtracker.project_management_backend.project.ProjectRole;
import com.pmtracker.project_management_backend.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.io.UnsupportedEncodingException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Живые обновления (4.15): {@code GET /api/realtime/stream} и то, кому и что по нему
 * доезжает.
 *
 * <p>Ломается этот код беззвучно. Сигнал, не дошедший до вкладки, выглядит ровно как
 * спокойный день в проекте: ни ошибки, ни записи в логах — просто у второго человека доска
 * такая же, как была. Сигнал, дошедший до лишнего, не выглядит вообще никак: он не несёт
 * данных, и заметить его можно только по лишнему запросу в панели разработчика. Поэтому
 * проверяется здесь не «работает ли поток», а именно границы: доходит своим, не доходит
 * чужим и перестаёт доходить исключённым.
 *
 * <p>Поток читается прямо из накопленного ответа MockMvc: асинхронный запрос не
 * диспатчится, {@code SseEmitter} пишет в тот же {@code MockHttpServletResponse}, и его
 * содержимое можно перечитывать по мере поступления. Ждать приходится всегда (см.
 * {@link #awaitStreamContaining}) — рассылка идёт после коммита и в другом потоке, ровно
 * как отправка почты.
 */
class RealtimeStreamIntegrationTest extends IntegrationTest {

    /** Сигнал уходит после коммита и в чужом потоке — «сейчас пусто» ничего не значит. */
    private static final Duration STREAM_TIMEOUT = Duration.ofSeconds(10);

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private RealtimeConnectionRegistry registry;

    private User owner;
    private User member;
    private User outsider;
    private Project project;

    private String ownerAuth;
    private String memberAuth;
    private String outsiderAuth;

    @BeforeEach
    void createProject() {
        owner = createUser("owner@example.com", "Владелец");
        member = createUser("member@example.com", "Участник");
        outsider = createUser("outsider@example.com", "Посторонний");

        project = new Project();
        project.setName("Realtime project");
        project.setSlug("realtime-project");
        project.setCreatedBy(owner);
        projectRepository.save(project);

        join(owner, ProjectRole.OWNER);
        join(member, ProjectRole.MEMBER);

        ownerAuth = "Bearer " + jwtService.generateAccessToken(owner);
        memberAuth = "Bearer " + jwtService.generateAccessToken(member);
        outsiderAuth = "Bearer " + jwtService.generateAccessToken(outsider);
    }

    // --------------------------------------------------------------- подключение

    @Test
    @DisplayName("аноним потока не получает")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get("/api/realtime/stream")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("поток открывается и сразу здоровается: клиенту нужно знать, что он подключён, а не что сервер молчит")
    void streamGreetsOnOpen() throws Exception {
        MvcResult stream = openStream(memberAuth);

        awaitStreamContaining(stream, "event:ready");
    }

    @Test
    @DisplayName("седьмая вкладка получает 429, а не седьмое соединение")
    void tooManyStreamsAreRejected() throws Exception {
        for (int i = 0; i < 6; i++) {
            openStream(outsiderAuth);
        }

        mockMvc.perform(get("/api/realtime/stream").header(AUTHORIZATION, outsiderAuth))
                .andExpect(status().isTooManyRequests())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("TOO_MANY_STREAMS"));
    }

    @Test
    @DisplayName("heartbeat уходит комментарием — данных в нём нет, а соединение он держит")
    void heartbeatWritesAComment() throws Exception {
        MvcResult stream = openStream(memberAuth);
        awaitStreamContaining(stream, "event:ready");

        registry.heartbeat();

        awaitStreamContaining(stream, ":ping");
    }

    // ----------------------------------------------------------------- кому доходит

    @Test
    @DisplayName("правка в проекте доезжает до второго участника")
    void projectChangeReachesTheOtherMember() throws Exception {
        MvcResult stream = openStream(memberAuth);
        awaitStreamContaining(stream, "event:ready");

        createTask("Задача, заведённая соседом");

        awaitStreamContaining(stream, "\"scope\":\"project\"");
        assertThat(content(stream)).contains("\"type\":\"task_created\"")
                .contains("\"projectId\":\"" + project.getId() + "\"")
                .contains("\"actorId\":\"" + owner.getId() + "\"");
    }

    @Test
    @DisplayName("в потоке нет самой задачи — только адрес того, что изменилось")
    void streamCarriesNoEntityData() throws Exception {
        MvcResult stream = openStream(memberAuth);
        awaitStreamContaining(stream, "event:ready");

        createTask("Секретный заголовок задачи");

        awaitStreamContaining(stream, "\"scope\":\"project\"");
        assertThat(content(stream)).doesNotContain("Секретный заголовок задачи");
    }

    @Test
    @DisplayName("постороннему правка чужого проекта не доезжает")
    void foreignProjectChangeDoesNotLeak() throws Exception {
        MvcResult mine = openStream(memberAuth);
        MvcResult theirs = openStream(outsiderAuth);
        awaitStreamContaining(mine, "event:ready");
        awaitStreamContaining(theirs, "event:ready");

        createTask("Задача в чужом для постороннего проекте");

        // Ждём не тишины, а того, что событие вообще дошло, — и только тогда смотрим, что у
        // постороннего его нет. Иначе тест был бы зелёным и в приложении, которое вовсе
        // ничего не рассылает.
        awaitStreamContaining(mine, "\"type\":\"task_created\"");
        assertThat(content(theirs)).doesNotContain("event:change");
    }

    @Test
    @DisplayName("исключённый перестаёт получать события сразу, а не после переподключения")
    void removedMemberStopsReceiving() throws Exception {
        MvcResult removed = openStream(memberAuth);
        // Поток владельца — это часы: он остаётся участником, и по нему видно, что рассылка
        // конкретного события уже отработала. Без него «у исключённого пусто» означало бы
        // всего лишь «мы посмотрели слишком рано».
        MvcResult witness = openStream(ownerAuth);
        awaitStreamContaining(removed, "event:ready");
        awaitStreamContaining(witness, "event:ready");

        mockMvc.perform(delete("/api/projects/" + project.getId() + "/members/" + member.getId())
                        .header(AUTHORIZATION, ownerAuth))
                .andExpect(status().isNoContent());
        String afterRemoval = content(removed);

        createTask("Задача после исключения");
        awaitStreamContaining(witness, "\"type\":\"task_created\"");

        assertThat(content(removed)).isEqualTo(afterRemoval);
    }

    @Test
    @DisplayName("личное уведомление приезжает своей областью, а не как правка проекта")
    void notificationArrivesOnItsOwnScope() throws Exception {
        MvcResult stream = openStream(memberAuth);
        awaitStreamContaining(stream, "event:ready");

        mockMvc.perform(post("/api/projects/" + project.getId() + "/tasks")
                        .header(AUTHORIZATION, ownerAuth)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"title":"Задача участнику","assigneeId":"%s"}""".formatted(member.getId())))
                .andExpect(status().isCreated());

        awaitStreamContaining(stream, "\"scope\":\"notification\"");
        assertThat(content(stream)).contains("\"type\":\"task_assigned\"");
    }

    // ------------------------------------------------------------------- фикстуры

    private MvcResult openStream(String auth) throws Exception {
        return mockMvc.perform(get("/api/realtime/stream").header(AUTHORIZATION, auth))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private void createTask(String title) throws Exception {
        mockMvc.perform(post("/api/projects/" + project.getId() + "/tasks")
                        .header(AUTHORIZATION, ownerAuth)
                        .contentType(APPLICATION_JSON)
                        .content("{\"title\":\"%s\"}".formatted(title)))
                .andExpect(status().isCreated());
    }

    private void awaitStreamContaining(MvcResult stream, String expected) {
        await().atMost(STREAM_TIMEOUT).until(() -> content(stream).contains(expected));
    }

    private String content(MvcResult stream) throws UnsupportedEncodingException {
        return stream.getResponse().getContentAsString();
    }

    private User createUser(String email, String firstName) {
        User user = new User();
        user.setEmail(email);
        user.setUsername(usernameFrom(email));
        user.setPasswordHash("$2a$10$fixture.hash.never.verified.by.these.tests......");
        user.setLastName("Тестов");
        user.setFirstName(firstName);
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
