package com.pmtracker.project_management_backend.report;

import com.pmtracker.project_management_backend.auth.JwtService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.UserRepository;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectMember;
import com.pmtracker.project_management_backend.project.ProjectMemberRepository;
import com.pmtracker.project_management_backend.project.ProjectRepository;
import com.pmtracker.project_management_backend.project.ProjectRole;
import com.pmtracker.project_management_backend.sprint.Sprint;
import com.pmtracker.project_management_backend.sprint.SprintRepository;
import com.pmtracker.project_management_backend.sprint.SprintStatus;
import com.pmtracker.project_management_backend.support.IntegrationTest;
import com.pmtracker.project_management_backend.task.Task;
import com.pmtracker.project_management_backend.task.TaskRepository;
import com.pmtracker.project_management_backend.task.TaskStatus;
import com.pmtracker.project_management_backend.task.TaskUrgency;
import com.pmtracker.project_management_backend.timelog.TimeLog;
import com.pmtracker.project_management_backend.timelog.TimeLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static com.pmtracker.project_management_backend.task.TaskStatus.DONE;
import static com.pmtracker.project_management_backend.task.TaskStatus.IN_PROGRESS;
import static com.pmtracker.project_management_backend.task.TaskStatus.NEW;
import static com.pmtracker.project_management_backend.task.TaskStatus.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.CONTENT_DISPOSITION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Отчёт по времени (4.10) и дашборд проекта (4.11): {@code /api/projects/{id}/reports/time},
 * {@code .../reports/time.csv} и {@code /api/projects/{id}/dashboard}.
 *
 * <p>Оба пункта — чистое чтение, поэтому проверять здесь стоит не «сохранилось ли», а то,
 * что легко посчитать неправильно и не заметить. У отчёта это границы периода (включительные
 * с обеих сторон), пустые дни в полосе (их подставляет сервис, в SQL их нет), фильтр по
 * участнику и часы задачи, уехавшей в корзину. У CSV — экранирование: описание записи пишет
 * любой участник проекта, и ячейка, начинающаяся с «=», в Excel становится формулой.
 *
 * <p>У дашборда главное — burndown и среднее время в статусе: обе величины не хранятся
 * нигде, а восстанавливаются проходом по ленте активности, и ошибиться в этом проходе можно
 * тихо. Поэтому история статусов в тестах ниже пишется в {@code project_activity} напрямую,
 * с проставленными вручную моментами времени: пройти этот путь, дёргая API в реальном
 * времени, нельзя — «вчера» на живых часах не наступает.
 */
class ReportIntegrationTest extends IntegrationTest {

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TimeLogRepository timeLogRepository;
    @Autowired private SprintRepository sprintRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private User owner;
    private User member;
    private User viewer;
    private User outsider;
    private Project project;
    private String ownerHeader;
    private String viewerHeader;
    private String outsiderHeader;

    @BeforeEach
    void createProject() {
        owner = saveUser("owner@example.com", "Яковлев", "Олег");
        member = saveUser("member@example.com", "Петров", "Пётр");
        viewer = saveUser("viewer@example.com", "Абрамов", "Игорь");
        outsider = saveUser("outsider@example.com", "Чужой", "Сергей");

        project = saveProject();
        join(owner, ProjectRole.OWNER);
        join(member, ProjectRole.MEMBER);
        join(viewer, ProjectRole.VIEWER);

        ownerHeader = "Bearer " + jwtService.generateAccessToken(owner);
        viewerHeader = "Bearer " + jwtService.generateAccessToken(viewer);
        outsiderHeader = "Bearer " + jwtService.generateAccessToken(outsider);
    }

    // --------------------------------------------------------------- отчёт по времени

    @Nested
    @DisplayName("отчёт по времени")
    class TimeReport {

        @Test
        @DisplayName("складывает часы по участникам, задачам и дням")
        void aggregatesHours() throws Exception {
            Task first = task("Первая", IN_PROGRESS);
            Task second = task("Вторая", NEW);
            logTime(first, owner, "3.00", LocalDate.of(2026, 9, 1));
            logTime(first, member, "2.50", LocalDate.of(2026, 9, 2));
            logTime(second, owner, "1.00", LocalDate.of(2026, 9, 2));

            report("from=2026-09-01&to=2026-09-03", ownerHeader).andExpect(status().isOk())
                    .andExpect(jsonPath("$.from").value("2026-09-01"))
                    .andExpect(jsonPath("$.to").value("2026-09-03"))
                    .andExpect(jsonPath("$.totalHours").value(6.5))
                    // Больше всех часов — сверху: у владельца 4, у участника 2.5.
                    .andExpect(jsonPath("$.byUser", hasSize(2)))
                    .andExpect(jsonPath("$.byUser[0].user.email").value("owner@example.com"))
                    .andExpect(jsonPath("$.byUser[0].hours").value(4.0))
                    .andExpect(jsonPath("$.byUser[0].entryCount").value(2))
                    .andExpect(jsonPath("$.byUser[1].hours").value(2.5))
                    // По задачам — тот же порядок «больше сверху».
                    .andExpect(jsonPath("$.byTask", hasSize(2)))
                    .andExpect(jsonPath("$.byTask[0].title").value("Первая"))
                    .andExpect(jsonPath("$.byTask[0].hours").value(5.5))
                    .andExpect(jsonPath("$.byTask[1].hours").value(1.0));
        }

        /**
         * Пустые дни обязаны быть в полосе: без них выходные схлопываются, и неделя из
         * двух рабочих дней выглядит на графике как полная.
         */
        @Test
        @DisplayName("в разрезе по дням есть все дни периода, включая пустые")
        void fillsEmptyDays() throws Exception {
            logTime(task("Задача", NEW), owner, "4.00", LocalDate.of(2026, 9, 3));

            report("from=2026-09-01&to=2026-09-03", ownerHeader).andExpect(status().isOk())
                    .andExpect(jsonPath("$.byDay", hasSize(3)))
                    .andExpect(jsonPath("$.byDay[0].day").value("2026-09-01"))
                    .andExpect(jsonPath("$.byDay[0].hours").value(0))
                    .andExpect(jsonPath("$.byDay[1].hours").value(0))
                    .andExpect(jsonPath("$.byDay[2].day").value("2026-09-03"))
                    .andExpect(jsonPath("$.byDay[2].hours").value(4.0));
        }

        /** Границы включительные: «с 1 по 2» — это два дня, а не один. */
        @Test
        @DisplayName("период отсекает записи за его пределами, а границы входят в него")
        void respectsInclusiveBounds() throws Exception {
            Task task = task("Задача", NEW);
            logTime(task, owner, "1.00", LocalDate.of(2026, 8, 31));
            logTime(task, owner, "2.00", LocalDate.of(2026, 9, 1));
            logTime(task, owner, "4.00", LocalDate.of(2026, 9, 2));
            logTime(task, owner, "8.00", LocalDate.of(2026, 9, 3));

            report("from=2026-09-01&to=2026-09-02", ownerHeader).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalHours").value(6.0));
        }

        @Test
        @DisplayName("userId сужает отчёт до одного участника")
        void filtersByUser() throws Exception {
            Task task = task("Задача", NEW);
            logTime(task, owner, "3.00", LocalDate.of(2026, 9, 1));
            logTime(task, member, "5.00", LocalDate.of(2026, 9, 1));

            report("from=2026-09-01&to=2026-09-02&userId=" + member.getId(), ownerHeader)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(member.getId().toString()))
                    .andExpect(jsonPath("$.totalHours").value(5.0))
                    .andExpect(jsonPath("$.byUser", hasSize(1)))
                    .andExpect(jsonPath("$.byUser[0].user.email").value("member@example.com"));
        }

        /**
         * Трекер везде считает задачу в корзине несуществующей, и отчёт — не исключение.
         * Часы возвращаются вместе с задачей, поэтому это не потеря данных, а согласованность.
         */
        @Test
        @DisplayName("часы задачи, уехавшей в корзину, из отчёта уходят")
        void skipsTrashedTasks() throws Exception {
            Task alive = task("Живая", NEW);
            Task trashed = task("Удалённая", NEW);
            logTime(alive, owner, "2.00", LocalDate.of(2026, 9, 1));
            logTime(trashed, owner, "7.00", LocalDate.of(2026, 9, 1));

            jdbcTemplate.update("update tasks set deleted_at = now() where id = ?", trashed.getId());

            report("from=2026-09-01&to=2026-09-02", ownerHeader).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalHours").value(2.0))
                    .andExpect(jsonPath("$.byTask", hasSize(1)))
                    .andExpect(jsonPath("$.byTask[0].title").value("Живая"));
        }

        /** Ссылка без параметров обязана открываться: границы выбирает сервер. */
        @Test
        @DisplayName("без параметров отдаёт последние 30 дней")
        void defaultsToLastThirtyDays() throws Exception {
            LocalDate today = LocalDate.now();

            report(null, ownerHeader).andExpect(status().isOk())
                    .andExpect(jsonPath("$.to").value(today.toString()))
                    .andExpect(jsonPath("$.from").value(today.minusDays(29).toString()))
                    .andExpect(jsonPath("$.userId").value(nullValue()));
        }

        @Test
        @DisplayName("начало позже конца и период длиннее года — 400")
        void rejectsInvalidRange() throws Exception {
            report("from=2026-09-10&to=2026-09-01", ownerHeader).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("REPORT_RANGE_INVALID"));

            report("from=2024-01-01&to=2026-01-01", ownerHeader).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("REPORT_RANGE_INVALID"));
        }

        /**
         * VIEWER читает отчёт наравне со всеми: сами записи времени ему и так видны на
         * карточке каждой задачи, а сумма не добавляет к ним ничего нового.
         */
        @Test
        @DisplayName("VIEWER читает отчёт, посторонний — 403")
        void checksMembership() throws Exception {
            report("from=2026-09-01&to=2026-09-02", viewerHeader).andExpect(status().isOk());

            report("from=2026-09-01&to=2026-09-02", outsiderHeader).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("NOT_A_PROJECT_MEMBER"));
        }
    }

    // ------------------------------------------------------------------- CSV-выгрузка

    @Nested
    @DisplayName("CSV-выгрузка")
    class CsvExport {

        @Test
        @DisplayName("отдаёт BOM, заголовок и строки записей, а имя файла — в Content-Disposition")
        void writesRows() throws Exception {
            Task task = task("Починить логин", IN_PROGRESS);
            logTime(task, owner, "2.50", LocalDate.of(2026, 9, 1), "Разбирался с токенами");

            MvcResult result = mockMvc.perform(get("/api/projects/{id}/reports/time.csv", project.getId())
                            .param("from", "2026-09-01").param("to", "2026-09-30")
                            .header(AUTHORIZATION, ownerHeader))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/csv"))
                    .andExpect(header().string(CONTENT_DISPOSITION,
                            containsString("time-report-report-project-2026-09-01_2026-09-30.csv")))
                    .andReturn();

            String csv = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);

            // BOM — ради Excel: без него он читает UTF-8 как ANSI, и кириллица превращается в кашу.
            assertThat(csv).startsWith("\uFEFF");
            assertThat(csv).contains("Дата,Участник,Email,Задача,Название задачи,Часы,Описание");
            assertThat(csv).contains("2026-09-01,Яковлев Олег,owner@example.com,#" + task.getTaskNumber()
                    + ",Починить логин,2.50,Разбирался с токенами");
        }

        /**
         * Описание записи пишет любой участник проекта, и ячейка, начинающаяся с «=», в
         * Excel и Sheets становится формулой — то есть выгрузка превращается в способ
         * выполнить чужой код на чужой машине. Апостроф гасит её, оставаясь незаметным.
         */
        @Test
        @DisplayName("ячейка-формула обезвреживается апострофом")
        void neutralizesFormulas() throws Exception {
            Task task = task("Задача", NEW);
            logTime(task, owner, "1.00", LocalDate.of(2026, 9, 1), "=1+1");

            String csv = csv("from=2026-09-01&to=2026-09-02");

            assertThat(csv).contains(",'=1+1");
            assertThat(csv).doesNotContain(",=1+1");
        }

        @Test
        @DisplayName("запятые, кавычки и переводы строк в описании экранируются по RFC 4180")
        void quotesSpecialCharacters() throws Exception {
            Task task = task("Задача", NEW);
            logTime(task, owner, "1.00", LocalDate.of(2026, 9, 1), "Смотрел \"логи\", потом\nправил");

            String csv = csv("from=2026-09-01&to=2026-09-02");

            assertThat(csv).contains("\"Смотрел \"\"логи\"\", потом\nправил\"");
        }

        @Test
        @DisplayName("посторонний не выгружает чужой проект")
        void checksMembership() throws Exception {
            mockMvc.perform(get("/api/projects/{id}/reports/time.csv", project.getId())
                            .header(AUTHORIZATION, outsiderHeader))
                    .andExpect(status().isForbidden());
        }
    }

    // ----------------------------------------------------------------------- дашборд

    @Nested
    @DisplayName("дашборд")
    class Dashboard {

        @Test
        @DisplayName("распределение по статусам содержит все шесть, включая пустые")
        void statusDistribution() throws Exception {
            task("Раз", NEW);
            task("Два", NEW);
            task("Три", IN_PROGRESS);
            task("Четыре", DONE);
            task("Пять", REJECTED);

            dashboard(null, ownerHeader).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalTasks").value(5))
                    // Открыто — всё, кроме DONE и REJECTED: то же определение, что у
                    // прогресса спринта.
                    .andExpect(jsonPath("$.openTasks").value(3))
                    .andExpect(jsonPath("$.statusDistribution", hasSize(6)))
                    .andExpect(jsonPath("$.statusDistribution[0].status").value("NEW"))
                    .andExpect(jsonPath("$.statusDistribution[0].count").value(2))
                    .andExpect(jsonPath("$.statusDistribution[2].status").value("PAUSED"))
                    .andExpect(jsonPath("$.statusDistribution[2].count").value(0));
        }

        /**
         * «Не назначено» — самая важная строка этого графика: она показывает, сколько
         * работы вообще ни на ком не висит, и потеряться из-за left join она не должна.
         */
        @Test
        @DisplayName("в распределении по исполнителям есть строка «не назначено»")
        void assigneeDistributionKeepsUnassigned() throws Exception {
            assign(task("Раз", NEW), member);
            assign(task("Два", DONE), member);
            task("Три", NEW);

            dashboard(null, ownerHeader).andExpect(status().isOk())
                    .andExpect(jsonPath("$.assigneeDistribution", hasSize(2)))
                    // Сверху тот, у кого больше незакрытого; у обоих по одной открытой
                    // задаче, дальше решает общее число.
                    .andExpect(jsonPath("$.assigneeDistribution[0].user.email").value("member@example.com"))
                    .andExpect(jsonPath("$.assigneeDistribution[0].openCount").value(1))
                    .andExpect(jsonPath("$.assigneeDistribution[0].totalCount").value(2))
                    .andExpect(jsonPath("$.assigneeDistribution[1].user").value(nullValue()))
                    .andExpect(jsonPath("$.assigneeDistribution[1].openCount").value(1));
        }

        @Test
        @DisplayName("без спринтов burndown пустой, а не выдуманный")
        void noSprintsMeansNoBurndown() throws Exception {
            task("Раз", NEW);

            dashboard(null, ownerHeader).andExpect(status().isOk())
                    .andExpect(jsonPath("$.burndown").value(nullValue()));
        }

        /**
         * Главный тест пункта: линия факта строится не по нынешним статусам задач, а по
         * тому, какими они были в каждый день окна. Здесь две задачи спринта, одну из
         * которых закрыли во второй день, — и это должно быть видно ровно во втором дне.
         */
        @Test
        @DisplayName("burndown восстанавливает остаток по истории статусов")
        void burndownReplaysHistory() throws Exception {
            LocalDate start = LocalDate.now().minusDays(2);
            LocalDate end = LocalDate.now();
            Sprint sprint = saveSprint("Спринт 1", start, end);

            Task closed = taskCreatedAt("Закрытая", DONE, start.minusDays(1));
            Task open = taskCreatedAt("Открытая", IN_PROGRESS, start.minusDays(1));
            putInSprint(sprint, closed, open);

            // Задачу закрыли в середине второго дня окна — до этого момента она считается
            // невыполненным обещанием, после — нет.
            recordStatusChange(closed, "IN_PROGRESS", "DONE", noon(start.plusDays(1)));

            dashboard(null, ownerHeader).andExpect(status().isOk())
                    .andExpect(jsonPath("$.burndown.sprint.name").value("Спринт 1"))
                    .andExpect(jsonPath("$.burndown.scope").value(2))
                    .andExpect(jsonPath("$.burndown.points", hasSize(3)))
                    .andExpect(jsonPath("$.burndown.points[0].date").value(start.toString()))
                    .andExpect(jsonPath("$.burndown.points[0].remaining").value(2))
                    .andExpect(jsonPath("$.burndown.points[1].remaining").value(1))
                    .andExpect(jsonPath("$.burndown.points[2].remaining").value(1))
                    // Идеальная линия — от объёма к нулю по всему окну.
                    .andExpect(jsonPath("$.burndown.points[0].ideal").value(2.0))
                    .andExpect(jsonPath("$.burndown.points[1].ideal").value(1.0))
                    .andExpect(jsonPath("$.burndown.points[2].ideal").value(0.0));
        }

        /**
         * Дни, которые ещё не наступили, приезжают с remaining = null — это «данных нет»,
         * а не «всё закрыто». Иначе спринт, начавшийся вчера, выглядел бы выполненным.
         */
        @Test
        @DisplayName("будущие дни спринта приезжают без остатка")
        void futureDaysHaveNoRemaining() throws Exception {
            Sprint sprint = saveSprint("Спринт 1", LocalDate.now(), LocalDate.now().plusDays(2));
            putInSprint(sprint, task("Раз", NEW));

            dashboard(null, ownerHeader).andExpect(status().isOk())
                    .andExpect(jsonPath("$.burndown.points", hasSize(3)))
                    .andExpect(jsonPath("$.burndown.points[0].remaining").value(1))
                    .andExpect(jsonPath("$.burndown.points[1].remaining").value(nullValue()))
                    .andExpect(jsonPath("$.burndown.points[2].remaining").value(nullValue()))
                    // План при этом известен на всё окно — он и есть план.
                    .andExpect(jsonPath("$.burndown.points[2].ideal").value(0.0));
        }

        @Test
        @DisplayName("sprintId выбирает спринт, чужой спринт — 400")
        void picksRequestedSprint() throws Exception {
            saveSprint("Текущий", LocalDate.now(), LocalDate.now().plusDays(5));
            Sprint planned = saveSprint("Следующий", LocalDate.now().plusDays(6), LocalDate.now().plusDays(10));
            jdbcTemplate.update("update sprints set status = ? where name = ?", SprintStatus.ACTIVE.name(), "Текущий");

            // Без параметра берётся идущий спринт.
            dashboard(null, ownerHeader).andExpect(status().isOk())
                    .andExpect(jsonPath("$.burndown.sprint.name").value("Текущий"));

            dashboard(planned.getId(), ownerHeader).andExpect(status().isOk())
                    .andExpect(jsonPath("$.burndown.sprint.name").value("Следующий"));

            Project other = saveProject("Другой проект", "other-project");
            Sprint foreign = saveSprint(other, "Чужой", LocalDate.now(), LocalDate.now().plusDays(1));
            dashboard(foreign.getId(), ownerHeader).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("SPRINT_PROJECT_MISMATCH"));
        }

        /**
         * Среднее считается по законченным отрезкам: задача пролежала в NEW от заведения
         * до перевода в работу. Текущий, ещё не закрытый отрезок длины не имеет и в
         * среднее не входит — иначе оно росло бы само по себе, пока никто ничего не делает.
         */
        @Test
        @DisplayName("среднее время в статусе считается по законченным отрезкам")
        void averageTimeInStatus() throws Exception {
            Instant createdAt = Instant.now().minusSeconds(24 * 3600);
            Task task = taskCreatedAt("Задача", IN_PROGRESS, createdAt);
            // Через четыре часа после заведения задачу взяли в работу и с тех пор не трогали:
            // NEW получает отрезок в 4 часа, IN_PROGRESS — ни одного.
            recordStatusChange(task, "NEW", "IN_PROGRESS", createdAt.plusSeconds(4 * 3600));

            dashboard(null, ownerHeader).andExpect(status().isOk())
                    .andExpect(jsonPath("$.averageTimeInStatus[0].status").value("NEW"))
                    .andExpect(jsonPath("$.averageTimeInStatus[0].averageHours").value(4.0))
                    .andExpect(jsonPath("$.averageTimeInStatus[0].sampleCount").value(1))
                    .andExpect(jsonPath("$.averageTimeInStatus[1].status").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.averageTimeInStatus[1].averageHours").value(nullValue()))
                    .andExpect(jsonPath("$.averageTimeInStatus[1].sampleCount").value(0));
        }

        @Test
        @DisplayName("VIEWER читает дашборд, посторонний — 403")
        void checksMembership() throws Exception {
            dashboard(null, viewerHeader).andExpect(status().isOk());

            dashboard(null, outsiderHeader).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("NOT_A_PROJECT_MEMBER"));
        }
    }

    // ---------------------------------------------------------------------- фикстуры

    private ResultActions report(String query, String authHeader) throws Exception {
        String url = "/api/projects/" + project.getId() + "/reports/time" + (query != null ? "?" + query : "");
        return mockMvc.perform(get(url).header(AUTHORIZATION, authHeader));
    }

    private String csv(String query) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/projects/" + project.getId() + "/reports/time.csv?" + query)
                        .header(AUTHORIZATION, ownerHeader))
                .andExpect(status().isOk())
                .andReturn();
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private ResultActions dashboard(UUID sprintId, String authHeader) throws Exception {
        String url = "/api/projects/" + project.getId() + "/dashboard"
                + (sprintId != null ? "?sprintId=" + sprintId : "");
        return mockMvc.perform(get(url).header(AUTHORIZATION, authHeader));
    }

    private User saveUser(String email, String lastName, String firstName) {
        User user = new User();
        user.setEmail(email);
        user.setUsername(usernameFrom(email));
        user.setPasswordHash("$2a$10$fixture.hash.never.verified.by.these.tests......");
        user.setLastName(lastName);
        user.setFirstName(firstName);
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private Project saveProject() {
        return saveProject("Report project", "report-project");
    }

    private Project saveProject(String name, String slug) {
        Project newProject = new Project();
        newProject.setName(name);
        newProject.setSlug(slug);
        newProject.setCreatedBy(owner);
        return projectRepository.save(newProject);
    }

    private void join(User user, ProjectRole role) {
        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(user);
        membership.setRole(role);
        projectMemberRepository.save(membership);
    }

    private Task task(String title, TaskStatus status) {
        Task task = new Task();
        task.setProject(project);
        task.setTitle(title);
        task.setStatus(status);
        task.setUrgency(TaskUrgency.MEDIUM);
        task.setPosition(0);
        task.setCreatedBy(owner);
        task.setTaskNumber(projectRepository.reserveNextTaskNumber(project.getId()));
        return taskRepository.save(task);
    }

    /**
     * Задача с проставленным задним числом created_at. Через JPA этого не сделать —
     * @PrePersist ставит «сейчас», — а восстановление истории именно от него и отсчитывает
     * первый отрезок жизни задачи.
     */
    private Task taskCreatedAt(String title, TaskStatus status, LocalDate createdOn) {
        return taskCreatedAt(title, status, noon(createdOn));
    }

    private Task taskCreatedAt(String title, TaskStatus status, Instant createdAt) {
        Task task = task(title, status);
        jdbcTemplate.update("update tasks set created_at = ? where id = ?",
                Timestamp.from(createdAt), task.getId());
        return task;
    }

    private void assign(Task task, User assignee) {
        task.setAssignee(assignee);
        taskRepository.save(task);
    }

    private Sprint saveSprint(String name, LocalDate from, LocalDate to) {
        return saveSprint(project, name, from, to);
    }

    private Sprint saveSprint(Project target, String name, LocalDate from, LocalDate to) {
        Sprint sprint = new Sprint();
        sprint.setProject(target);
        sprint.setName(name);
        sprint.setStartDate(from);
        sprint.setEndDate(to);
        sprint.setStatus(SprintStatus.PLANNED);
        sprint.setCreatedBy(owner);
        return sprintRepository.save(sprint);
    }

    private void putInSprint(Sprint sprint, Task... tasks) {
        for (Task task : tasks) {
            jdbcTemplate.update("update tasks set sprint_id = ? where id = ?", sprint.getId(), task.getId());
        }
    }

    private void logTime(Task task, User user, String hours, LocalDate spentOn) {
        logTime(task, user, hours, spentOn, null);
    }

    private void logTime(Task task, User user, String hours, LocalDate spentOn, String description) {
        TimeLog log = new TimeLog();
        log.setTask(task);
        log.setUser(user);
        log.setHours(new BigDecimal(hours));
        log.setSpentOn(spentOn);
        log.setDescription(description);
        timeLogRepository.save(log);
    }

    /**
     * Событие смены статуса с проставленным вручную моментом — ровно в том виде, в каком
     * его пишет TaskService (тип, task_id и payload с old/new). Через API этого не
     * добиться: там момент всегда «сейчас», а проверять надо позавчерашний.
     */
    private void recordStatusChange(Task task, String oldStatus, String newStatus, Instant at) {
        jdbcTemplate.update("""
                insert into project_activity (project_id, actor_id, type, task_id, payload, created_at)
                values (?, ?, 'task_status_changed', ?, ?::jsonb, ?)
                """,
                project.getId(), owner.getId(), task.getId(),
                "{\"old\": \"%s\", \"new\": \"%s\"}".formatted(oldStatus, newStatus),
                Timestamp.from(at));
    }

    /** Полдень указанного дня — момент заведомо внутри суток, а не на их границе. */
    private static Instant noon(LocalDate day) {
        return day.atStartOfDay(ZONE).plusHours(12).toInstant();
    }
}
