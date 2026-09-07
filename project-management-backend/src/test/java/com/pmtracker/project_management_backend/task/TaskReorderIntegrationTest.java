package com.pmtracker.project_management_backend.task;

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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static com.pmtracker.project_management_backend.task.TaskStatus.DONE;
import static com.pmtracker.project_management_backend.task.TaskStatus.IN_PROGRESS;
import static com.pmtracker.project_management_backend.task.TaskStatus.NEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Перетаскивание карточек на канбане: {@code PATCH /api/tasks/{id}/status}.
 * <p>
 * Логика короткая, но крайних случаев в ней больше, чем где-либо ещё в домене, и все они
 * молчаливые: перепутанная граница индекса или забытый пересчёт покинутой колонки не роняет
 * запрос и не портит данные — карточки просто начинают прыгать не туда, причём заметить это
 * можно только глазами и только если знаешь, куда смотреть.
 * <p>
 * Проверяется всегда одно и то же: полный состав затронутых колонок ПОСЛЕ операции, вместе с
 * номерами позиций. Именно полный — «задача оказалась там, где просили» само по себе ничего не
 * доказывает, вопрос в том, что стало с её соседями и с колонкой, которую она покинула.
 * Колонки читаются прямо из БД, а не из ответа: ответ показывает одну задачу, а поехать может
 * любая.
 * <p>
 * Позиции при создании задач намеренно расставляются вручную, в том числе с дырами: в проде
 * они и получаются дырявыми, потому что {@code nextPosition} считает максимум по всему
 * {@code (project, status)}, не различая top-level задачи и подзадачи разных родителей
 * (см. комментарий к {@code findSiblingsByStatus}). Пересчёт при первом же перетаскивании
 * обязан это выправить.
 */
class TaskReorderIntegrationTest extends IntegrationTest {

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User owner;
    private Project project;
    private String authHeader;

    @BeforeEach
    void createProject() {
        owner = new User();
        owner.setEmail("owner@example.com");
        owner.setUsername(usernameFrom("owner@example.com"));
        owner.setPasswordHash("$2a$10$fixture.hash.never.verified.by.these.tests......");
        owner.setLastName("Тестов");
        owner.setFirstName("Владелец");
        owner.setEmailVerified(true);
        userRepository.save(owner);

        project = new Project();
        project.setName("Kanban project");
        project.setSlug("kanban-project");
        project.setCreatedBy(owner);
        projectRepository.save(project);

        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(owner);
        membership.setRole(ProjectRole.OWNER);
        projectMemberRepository.save(membership);

        authHeader = "Bearer " + jwtService.generateAccessToken(owner);
    }

    // ------------------------------------------------- перестановка внутри одной колонки

    @Nested
    @DisplayName("внутри своей колонки")
    class WithinTheSameColumn {

        @Test
        @DisplayName("в начало — остальные сдвигаются вниз")
        void movesToTheStart() throws Exception {
            topLevel("A", NEW, 0);
            topLevel("B", NEW, 1);
            Task c = topLevel("C", NEW, 2);

            move(c, NEW, 0, NEW).andExpect(status().isOk())
                    .andExpect(jsonPath("$.position").value(0));

            assertThat(topLevelColumn(NEW)).containsExactly("C@0", "A@1", "B@2");
        }

        @Test
        @DisplayName("в конец — индекс, равный размеру колонки без самой задачи, допустим")
        void movesToTheEnd() throws Exception {
            Task a = topLevel("A", NEW, 0);
            topLevel("B", NEW, 1);
            topLevel("C", NEW, 2);

            // Колонка без самой A — это две задачи, то есть 2 здесь означает «в самый конец».
            move(a, NEW, 2, NEW).andExpect(status().isOk());

            assertThat(topLevelColumn(NEW)).containsExactly("B@0", "C@1", "A@2");
        }

        @Test
        @DisplayName("в середину")
        void movesToTheMiddle() throws Exception {
            Task a = topLevel("A", NEW, 0);
            topLevel("B", NEW, 1);
            topLevel("C", NEW, 2);

            move(a, NEW, 1, NEW).andExpect(status().isOk());

            assertThat(topLevelColumn(NEW)).containsExactly("B@0", "A@1", "C@2");
        }

        @Test
        @DisplayName("на своё же место — порядок не меняется")
        void movingOntoItsOwnPositionIsANoOp() throws Exception {
            topLevel("A", NEW, 0);
            Task b = topLevel("B", NEW, 1);
            topLevel("C", NEW, 2);

            move(b, NEW, 1, NEW).andExpect(status().isOk());

            assertThat(topLevelColumn(NEW)).containsExactly("A@0", "B@1", "C@2");
        }

        @Test
        @DisplayName("дырявые позиции схлопываются в 0..n-1 при первом же перетаскивании")
        void renumbersGapsLeftBehindByTaskCreation() throws Exception {
            Task a = topLevel("A", NEW, 0);
            topLevel("B", NEW, 5);
            topLevel("C", NEW, 9);

            move(a, NEW, 2, NEW).andExpect(status().isOk());

            assertThat(topLevelColumn(NEW)).containsExactly("B@0", "C@1", "A@2");
        }

        @Test
        @DisplayName("индекс за пределами колонки — 400, порядок не тронут")
        void rejectsIndexPastTheEnd() throws Exception {
            Task a = topLevel("A", NEW, 0);
            topLevel("B", NEW, 1);
            topLevel("C", NEW, 2);

            // Без самой A в колонке две задачи, значит максимум допустимого индекса — 2.
            move(a, NEW, 3, NEW)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_TARGET_POSITION"));

            assertThat(topLevelColumn(NEW)).containsExactly("A@0", "B@1", "C@2");
        }

        @Test
        @DisplayName("отрицательный индекс отсекается валидацией DTO, до сервиса не доходит")
        void rejectsNegativeIndex() throws Exception {
            Task a = topLevel("A", NEW, 0);
            topLevel("B", NEW, 1);

            move(a, NEW, -1, NEW)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

            assertThat(topLevelColumn(NEW)).containsExactly("A@0", "B@1");
        }

        @Test
        @DisplayName("соседние колонки не трогаются")
        void leavesOtherColumnsAlone() throws Exception {
            Task a = topLevel("A", NEW, 0);
            topLevel("B", NEW, 1);
            topLevel("X", IN_PROGRESS, 0);
            topLevel("Y", IN_PROGRESS, 1);

            move(a, NEW, 1, NEW).andExpect(status().isOk());

            assertThat(topLevelColumn(NEW)).containsExactly("B@0", "A@1");
            assertThat(topLevelColumn(IN_PROGRESS)).containsExactly("X@0", "Y@1");
        }
    }

    // --------------------------------------------------------- перенос в другую колонку

    @Nested
    @DisplayName("между колонками")
    class BetweenColumns {

        @Test
        @DisplayName("в пустую колонку")
        void movesIntoAnEmptyColumn() throws Exception {
            Task a = topLevel("A", NEW, 0);
            topLevel("B", NEW, 1);

            move(a, IN_PROGRESS, 0, NEW).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.position").value(0));

            assertThat(topLevelColumn(NEW)).containsExactly("B@0");
            assertThat(topLevelColumn(IN_PROGRESS)).containsExactly("A@0");
        }

        @Test
        @DisplayName("в середину непустой колонки — раздвигает её и пересчитывает покинутую")
        void movesIntoTheMiddleOfANonEmptyColumn() throws Exception {
            topLevel("A", NEW, 0);
            Task b = topLevel("B", NEW, 1);
            topLevel("C", NEW, 2);
            topLevel("X", IN_PROGRESS, 0);
            topLevel("Y", IN_PROGRESS, 1);

            move(b, IN_PROGRESS, 1, NEW).andExpect(status().isOk());

            // Покинутая колонка обязана сомкнуться: дыры на месте ушедшей задачи быть не должно.
            assertThat(topLevelColumn(NEW)).containsExactly("A@0", "C@1");
            assertThat(topLevelColumn(IN_PROGRESS)).containsExactly("X@0", "B@1", "Y@2");
        }

        @Test
        @DisplayName("в конец непустой колонки — индекс, равный её размеру, допустим")
        void appendsToTheEndOfANonEmptyColumn() throws Exception {
            Task a = topLevel("A", NEW, 0);
            topLevel("X", IN_PROGRESS, 0);
            topLevel("Y", IN_PROGRESS, 1);

            // В целевой колонке две задачи, и 2 — это «после последней», а не «за пределами».
            move(a, IN_PROGRESS, 2, NEW).andExpect(status().isOk());

            assertThat(topLevelColumn(NEW)).isEmpty();
            assertThat(topLevelColumn(IN_PROGRESS)).containsExactly("X@0", "Y@1", "A@2");
        }

        @Test
        @DisplayName("индекс за пределами целевой колонки — 400, обе колонки не тронуты")
        void rejectsIndexPastTheEndOfTheTargetColumn() throws Exception {
            Task a = topLevel("A", NEW, 0);
            topLevel("B", NEW, 1);
            topLevel("X", IN_PROGRESS, 0);

            move(a, IN_PROGRESS, 2, NEW)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_TARGET_POSITION"));

            assertThat(topLevelColumn(NEW)).containsExactly("A@0", "B@1");
            assertThat(topLevelColumn(IN_PROGRESS)).containsExactly("X@0");
        }

        @Test
        @DisplayName("последняя задача уходит из колонки — колонка остаётся пустой, без осадка")
        void emptiesTheColumnItLeaves() throws Exception {
            Task only = topLevel("Only", NEW, 3);

            move(only, DONE, 0, NEW).andExpect(status().isOk());

            assertThat(topLevelColumn(NEW)).isEmpty();
            assertThat(topLevelColumn(DONE)).containsExactly("Only@0");
        }
    }

    // ------------------------------------------------------------- колонки разных родителей

    @Nested
    @DisplayName("подзадачи")
    class SubtaskColumns {

        @Test
        @DisplayName("колонка родителя — это только его подзадачи, не соседей и не top-level")
        void columnsOfDifferentParentsAreIndependent() throws Exception {
            Task first = topLevel("P1", DONE, 0);
            Task second = topLevel("P2", DONE, 1);
            topLevel("T1", NEW, 0);
            topLevel("T2", NEW, 1);
            subtask(first, "a1", NEW, 0);
            Task a2 = subtask(first, "a2", NEW, 1);
            subtask(second, "b1", NEW, 0);
            subtask(second, "b2", NEW, 1);

            // Все шесть задач в статусе NEW лежат в одном (project, status), но на доске это
            // три независимые колонки. Перестановка в одной не должна задеть две другие.
            move(a2, NEW, 0, NEW).andExpect(status().isOk());

            assertThat(subtaskColumn(first, NEW)).containsExactly("a2@0", "a1@1");
            assertThat(subtaskColumn(second, NEW)).containsExactly("b1@0", "b2@1");
            assertThat(topLevelColumn(NEW)).containsExactly("T1@0", "T2@1");
        }

        @Test
        @DisplayName("подзадача переезжает между статусами внутри своего родителя")
        void subtaskMovesBetweenItsOwnParentsColumns() throws Exception {
            Task parent = topLevel("P", DONE, 0);
            Task other = topLevel("Other", DONE, 1);
            subtask(parent, "a1", NEW, 0);
            Task a2 = subtask(parent, "a2", NEW, 1);
            subtask(parent, "done1", IN_PROGRESS, 0);
            subtask(other, "b1", IN_PROGRESS, 0);

            move(a2, IN_PROGRESS, 0, NEW).andExpect(status().isOk());

            assertThat(subtaskColumn(parent, NEW)).containsExactly("a1@0");
            assertThat(subtaskColumn(parent, IN_PROGRESS)).containsExactly("a2@0", "done1@1");
            assertThat(subtaskColumn(other, IN_PROGRESS)).containsExactly("b1@0");
        }

        @Test
        @DisplayName("подзадача не переезжает к другому родителю: статус меняется, родитель нет")
        void reorderNeverReparentsATask() throws Exception {
            Task parent = topLevel("P", DONE, 0);
            Task a1 = subtask(parent, "a1", NEW, 0);

            move(a1, IN_PROGRESS, 0, NEW).andExpect(status().isOk())
                    .andExpect(jsonPath("$.parentTaskId").value(parent.getId().toString()));

            assertThat(subtaskColumn(parent, IN_PROGRESS)).containsExactly("a1@0");
        }
    }

    // ------------------------------------------------------- одновременные перетаскивания

    @Nested
    @DisplayName("конкурентные перемещения")
    class ConcurrentMoves {

        /**
         * Ровно тот случай, ради которого в запросе вообще есть expectedStatus: две вкладки
         * (или два человека) держат перед глазами одну и ту же доску, первый перетаскивает
         * карточку, второй тащит её же — но исходя из того, что видел до этого.
         */
        @Test
        @DisplayName("второе перетаскивание с устаревшим expectedStatus — 409, доска не меняется")
        void staleExpectedStatusIsRejected() throws Exception {
            Task a = topLevel("A", NEW, 0);
            topLevel("B", NEW, 1);

            move(a, IN_PROGRESS, 0, NEW).andExpect(status().isOk());

            move(a, DONE, 0, NEW)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("TASK_STATUS_CONFLICT"));

            assertThat(topLevelColumn(NEW)).containsExactly("B@0");
            assertThat(topLevelColumn(IN_PROGRESS)).containsExactly("A@0");
            assertThat(topLevelColumn(DONE)).isEmpty();
        }

        @Test
        @DisplayName("перетаскивание с актуальным expectedStatus проходит и после чужого хода")
        void refreshedExpectedStatusSucceeds() throws Exception {
            Task a = topLevel("A", NEW, 0);
            topLevel("B", NEW, 1);

            move(a, IN_PROGRESS, 0, NEW).andExpect(status().isOk());
            // Вторая вкладка перечитала доску и повторила перетаскивание — теперь оно валидно.
            move(a, DONE, 0, IN_PROGRESS).andExpect(status().isOk());

            assertThat(topLevelColumn(NEW)).containsExactly("B@0");
            assertThat(topLevelColumn(IN_PROGRESS)).isEmpty();
            assertThat(topLevelColumn(DONE)).containsExactly("A@0");
        }

        /**
         * Гонка на самом счётчике позиций: обе задачи создавались независимо и вполне могли
         * получить одинаковый position (nextPosition читает максимум и пишет обратно без
         * блокировки — известная и сознательно оставленная гонка, см. комментарий к
         * findMaxPositionForStatus). Первое же перетаскивание обязано развести их.
         */
        @Test
        @DisplayName("дубликаты позиций, оставшиеся от гонки при создании, расходятся при первом drag")
        void duplicatePositionsAreResolvedOnTheNextDrag() throws Exception {
            topLevel("A", NEW, 0);
            topLevel("B", NEW, 0);
            Task c = topLevel("C", NEW, 0);

            move(c, NEW, 0, NEW).andExpect(status().isOk());

            assertThat(topLevelColumn(NEW)).containsExactly("C@0", "A@1", "B@2");
        }
    }

    // ------------------------------------------------------------------------- хелперы

    private ResultActions move(Task task, TaskStatus to, int position, TaskStatus expectedStatus) throws Exception {
        return mockMvc.perform(patch("/api/tasks/" + task.getId() + "/status")
                .header(AUTHORIZATION, authHeader)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"status":"%s","position":%d,"expectedStatus":"%s"}"""
                        .formatted(to, position, expectedStatus)));
    }

    /**
     * Колонка доски как её видит пользователь — «title@position» в порядке позиций. Читается
     * из БД, а не из ответа API: ответ показывает одну задачу, а разъехаться может любая.
     * <p>
     * {@code IS NOT DISTINCT FROM} вместо {@code = ?}: у top-level задач parent_task_id равен
     * NULL, и обычное сравнение с NULL не даёт истины ни для чего.
     */
    private List<String> column(TaskStatus status, UUID parentTaskId) {
        return jdbcTemplate.queryForList("""
                SELECT title || '@' || "position"
                FROM tasks
                WHERE project_id = ? AND status = ? AND parent_task_id IS NOT DISTINCT FROM ?
                ORDER BY "position", title
                """, String.class, project.getId(), status.name(), parentTaskId);
    }

    private List<String> topLevelColumn(TaskStatus status) {
        return column(status, null);
    }

    private List<String> subtaskColumn(Task parent, TaskStatus status) {
        return column(status, parent.getId());
    }

    private Task topLevel(String title, TaskStatus status, int position) {
        return saveTask(title, status, position, null);
    }

    private Task subtask(Task parent, String title, TaskStatus status, int position) {
        return saveTask(title, status, position, parent);
    }

    private Task saveTask(String title, TaskStatus status, int position, Task parent) {
        Task task = new Task();
        task.setProject(project);
        task.setParentTask(parent);
        task.setTaskNumber(projectRepository.reserveNextTaskNumber(project.getId()));
        task.setTitle(title);
        task.setStatus(status);
        task.setUrgency(TaskUrgency.MEDIUM);
        // Позиция задаётся явно, а не через nextPosition: половина тестов здесь как раз про то,
        // что бывает с неидеальной нумерацией — дырами и дубликатами.
        task.setPosition(position);
        task.setCreatedBy(owner);
        return taskRepository.save(task);
    }
}
