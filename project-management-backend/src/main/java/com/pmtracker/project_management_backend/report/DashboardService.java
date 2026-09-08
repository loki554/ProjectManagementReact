package com.pmtracker.project_management_backend.report;

import com.pmtracker.project_management_backend.activity.ProjectActivityRepository;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.UserRepository;
import com.pmtracker.project_management_backend.auth.dto.UserSummary;
import com.pmtracker.project_management_backend.common.exception.SprintNotFoundException;
import com.pmtracker.project_management_backend.common.exception.SprintProjectMismatchException;
import com.pmtracker.project_management_backend.project.ProjectAccessService;
import com.pmtracker.project_management_backend.report.dto.AssigneeLoadRow;
import com.pmtracker.project_management_backend.report.dto.BurndownPoint;
import com.pmtracker.project_management_backend.report.dto.BurndownResponse;
import com.pmtracker.project_management_backend.report.dto.DashboardResponse;
import com.pmtracker.project_management_backend.report.dto.StatusCountRow;
import com.pmtracker.project_management_backend.report.dto.StatusDurationRow;
import com.pmtracker.project_management_backend.sprint.Sprint;
import com.pmtracker.project_management_backend.sprint.SprintRepository;
import com.pmtracker.project_management_backend.sprint.SprintStatus;
import com.pmtracker.project_management_backend.sprint.dto.SprintSummary;
import com.pmtracker.project_management_backend.task.TaskRepository;
import com.pmtracker.project_management_backend.task.TaskStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Дашборд проекта (4.11).
 *
 * <p><b>Ничего нового не пишется.</b> Всё, что показывает дашборд, уже лежит в базе:
 * распределения — это group by по задачам, а burndown и «среднее время в статусе»
 * восстанавливаются по ленте активности, куда события task_status_changed попадают с V13.
 * Отдельная таблица снимков («на такой-то день было столько-то открытых») дала бы более
 * быстрый ответ, но потребовала бы фонового задания, которое обязано отработать каждый
 * день, иначе в графике появляется дыра, — и заводить её стоит тогда, когда пересчёт на
 * лету перестанет укладываться в запрос, а не заранее.
 *
 * <p><b>Почему считается в памяти.</b> И burndown, и время в статусе — это проход по ряду
 * переходов каждой задачи: «была NEW до вторника, потом IN_PROGRESS до пятницы». Выразить
 * такой проход в SQL можно (оконные функции с lag), но читать и править его пришлось бы
 * как ребус, а объёмы здесь проектные — задачи проекта и их смены статуса, то есть тысячи
 * строк, а не миллионы. Обмен понятный: несколько миллисекунд на свёртку против запроса,
 * в который никто не захочет заглядывать.
 *
 * <p><b>Про права.</b> Как и отчёт по времени (4.10) — любой участник, включая VIEWER:
 * дашборд не показывает ничего, чего не видно на доске и в списке задач, он только считает.
 */
@Service
public class DashboardService {

    /** «Задача закрыта» — то же определение, что у прогресса спринта (см. SprintRepository). */
    private static final List<TaskStatus> CLOSED_STATUSES = TaskStatus.INACTIVE;

    /**
     * Часовой пояс, в котором день спринта считается днём. Даты спринта — календарные (V31),
     * и вопрос «что было закрыто к концу вторника» без пояса не определён. Берём пояс
     * сервера — тот же, в котором работает планировщик напоминаний и в котором участники
     * вводят spent_on у записей времени; заводить ради дашборда собственный (UTC) значило бы
     * получить график, съезжающий на день относительно всего остального в трекере.
     */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final TaskRepository taskRepository;
    private final SprintRepository sprintRepository;
    private final ProjectActivityRepository projectActivityRepository;
    private final UserRepository userRepository;
    private final ProjectAccessService projectAccessService;

    public DashboardService(TaskRepository taskRepository,
                            SprintRepository sprintRepository,
                            ProjectActivityRepository projectActivityRepository,
                            UserRepository userRepository,
                            ProjectAccessService projectAccessService) {
        this.taskRepository = taskRepository;
        this.sprintRepository = sprintRepository;
        this.projectActivityRepository = projectActivityRepository;
        this.userRepository = userRepository;
        this.projectAccessService = projectAccessService;
    }

    /**
     * @param sprintId спринт для burndown; null — берётся текущий (см. resolveSprint)
     */
    @Transactional(readOnly = true)
    public DashboardResponse dashboard(User currentUser, UUID projectId, UUID sprintId) {
        projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);

        List<StatusCountRow> statusDistribution = loadStatusDistribution(projectId);
        long totalTasks = statusDistribution.stream().mapToLong(StatusCountRow::count).sum();
        long openTasks = statusDistribution.stream()
                .filter(row -> !CLOSED_STATUSES.contains(row.status()))
                .mapToLong(StatusCountRow::count)
                .sum();

        // Переходы читаются один раз на весь запрос: они нужны и среднему времени в
        // статусе (по всему проекту), и burndown (по составу одного спринта), а второй
        // такой же запрос ради подмножества первого — лишний.
        Map<UUID, List<ProjectActivityRepository.StatusTransition>> transitions =
                projectActivityRepository.findStatusTransitions(projectId).stream()
                        .collect(Collectors.groupingBy(ProjectActivityRepository.StatusTransition::getTaskId,
                                LinkedHashMap::new, Collectors.toList()));

        return new DashboardResponse(
                totalTasks,
                openTasks,
                statusDistribution,
                loadAssigneeDistribution(projectId),
                averageTimeInStatus(projectId, transitions),
                buildBurndown(projectId, sprintId, transitions));
    }

    // ------------------------------------------------------------------- распределения

    private List<StatusCountRow> loadStatusDistribution(UUID projectId) {
        Map<TaskStatus, Long> counts = new EnumMap<>(TaskStatus.class);
        for (TaskRepository.StatusCount row : taskRepository.countByStatus(projectId)) {
            counts.put(row.getStatus(), row.getTaskCount());
        }
        // Порядок — объявленный в TaskStatus, то есть тот же, что у колонок доски: график,
        // в котором статусы идут в другом порядке, чем на доске, читается как чужой.
        return Arrays.stream(TaskStatus.values())
                .map(status -> new StatusCountRow(status, counts.getOrDefault(status, 0L)))
                .toList();
    }

    private List<AssigneeLoadRow> loadAssigneeDistribution(UUID projectId) {
        List<TaskRepository.AssigneeLoad> loads = taskRepository.countByAssignee(projectId, CLOSED_STATUSES);

        Map<UUID, User> users = userRepository.findAllById(loads.stream()
                        .map(TaskRepository.AssigneeLoad::getUserId)
                        .filter(Objects::nonNull)
                        .toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        return loads.stream()
                .map(load -> new AssigneeLoadRow(
                        load.getUserId() != null && users.containsKey(load.getUserId())
                                ? UserSummary.from(users.get(load.getUserId()))
                                : null,
                        load.getOpenCount(),
                        load.getTotalCount()))
                // Сверху — на ком больше всего незакрытого: это тот вопрос, ради которого
                // на график вообще смотрят. Строка «не назначено» участвует в сортировке
                // наравне со всеми и всплывает наверх ровно тогда, когда должна.
                .sorted(Comparator.comparingLong(AssigneeLoadRow::openCount).reversed()
                        .thenComparing(Comparator.comparingLong(AssigneeLoadRow::totalCount).reversed()))
                .toList();
    }

    // --------------------------------------------------------- среднее время в статусе

    /**
     * Сколько задача в среднем проводит в каждом статусе.
     *
     * <p>Считается по <i>законченным</i> отрезкам: задача пробыла в IN_PROGRESS от перевода
     * в него до перевода из него. Текущий, ещё не закрытый отрезок в среднее не входит — у
     * него пока нет длины, и подставлять вместо неё «до сих пор» значило бы, что среднее
     * растёт само по себе каждую минуту, пока никто ничего не делает. Задачи, застрявшие в
     * статусе навсегда, при этом не теряются: их видно в распределении по статусам рядом.
     *
     * <p>Первый отрезок каждой задачи начинается не с перехода, а с её создания: время от
     * заведения до первого перевода в работу — это и есть «сколько задача пролежала в NEW»,
     * и выкидывать его значило бы обнулить статистику самого частого статуса.
     */
    private List<StatusDurationRow> averageTimeInStatus(
            UUID projectId, Map<UUID, List<ProjectActivityRepository.StatusTransition>> transitions) {

        Map<TaskStatus, Duration> totals = new EnumMap<>(TaskStatus.class);
        Map<TaskStatus, Long> samples = new EnumMap<>(TaskStatus.class);

        for (TaskRepository.TaskTimelineRow task : taskRepository.findTimelineByProjectId(projectId)) {
            Instant intervalStart = task.getCreatedAt();
            TaskStatus current = initialStatus(task, transitions.get(task.getId()));

            for (ProjectActivityRepository.StatusTransition transition : transitions.getOrDefault(task.getId(), List.of())) {
                TaskStatus next = parseStatus(transition.getNewStatus());
                if (next == null) {
                    continue;
                }
                if (current != null) {
                    totals.merge(current, Duration.between(intervalStart, transition.getChangedAt()), Duration::plus);
                    samples.merge(current, 1L, Long::sum);
                }
                intervalStart = transition.getChangedAt();
                current = next;
            }
        }

        return Arrays.stream(TaskStatus.values())
                .map(status -> {
                    long count = samples.getOrDefault(status, 0L);
                    return new StatusDurationRow(status, count == 0 ? null : averageHours(totals.get(status), count), count);
                })
                .toList();
    }

    /**
     * Статус, с которого начинается ряд задачи. У задачи с историей это «старое» значение
     * её первого перехода — оно записано в самом событии, и лезть за ним больше некуда;
     * у задачи, которую ни разу не переводили, — её нынешний статус.
     */
    private static TaskStatus initialStatus(TaskRepository.TaskTimelineRow task,
                                            List<ProjectActivityRepository.StatusTransition> taskTransitions) {
        if (taskTransitions == null || taskTransitions.isEmpty()) {
            return task.getStatus();
        }
        TaskStatus first = parseStatus(taskTransitions.get(0).getOldStatus());
        return first != null ? first : task.getStatus();
    }

    /**
     * Строка статуса из payload события. null для всего, что не разбирается: payload — это
     * jsonb без схемы, там лежит снимок момента, и статус, который когда-то существовал, а
     * потом исчез из TaskStatus, не должен ронять дашборд.
     */
    private static TaskStatus parseStatus(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return TaskStatus.valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static BigDecimal averageHours(Duration total, long count) {
        // Через минуты, а не через часы: у отрезка в двадцать минут целочисленное деление
        // часов дало бы ноль ещё до усреднения.
        double minutes = (double) total.toMinutes() / count;
        return BigDecimal.valueOf(minutes / 60).setScale(1, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------------ burndown

    /**
     * Спринт, по которому строится линия.
     *
     * <p>Явно выбранный — если его прислали. Иначе идущий: «чем мы сейчас заняты» —
     * основной вопрос к этому графику. Если идущего нет, берётся ближайший к сегодняшнему
     * дню: сначала последний из уже начавшихся (проект, где спринт закрыли в пятницу, в
     * понедельник должен показывать именно его, а не пустое место), а если не начинался ни
     * один — ближайший запланированный. Спринты в проекте есть, а показать нечего — ответ,
     * которого дашборд давать не должен ни при каком их наборе.
     */
    private Optional<Sprint> resolveSprint(UUID projectId, UUID sprintId) {
        if (sprintId != null) {
            Sprint sprint = sprintRepository.findById(sprintId).orElseThrow(SprintNotFoundException::new);
            if (!sprint.getProject().getId().equals(projectId)) {
                throw new SprintProjectMismatchException();
            }
            return Optional.of(sprint);
        }

        Optional<Sprint> active = sprintRepository.findByProjectIdAndStatus(projectId, SprintStatus.ACTIVE);
        if (active.isPresent()) {
            return active;
        }

        LocalDate today = LocalDate.now(ZONE);
        List<Sprint> sprints = sprintRepository.findByProjectIdOrderByStartDateAsc(projectId);
        return sprints.stream()
                .filter(sprint -> !sprint.getStartDate().isAfter(today))
                .max(Comparator.comparing(Sprint::getStartDate))
                .or(() -> sprints.stream().min(Comparator.comparing(Sprint::getStartDate)));
    }

    private BurndownResponse buildBurndown(UUID projectId, UUID sprintId,
                                           Map<UUID, List<ProjectActivityRepository.StatusTransition>> transitions) {
        Optional<Sprint> resolved = resolveSprint(projectId, sprintId);
        if (resolved.isEmpty()) {
            return null;
        }
        Sprint sprint = resolved.get();

        List<TaskRepository.TaskTimelineRow> tasks = taskRepository.findTimelineBySprintId(sprint.getId());
        List<LocalDate> days = daysOf(sprint.getStartDate(), sprint.getEndDate());
        LocalDate today = LocalDate.now(ZONE);

        // Для каждой задачи — заранее посчитанный ряд «с какого момента какой статус»,
        // чтобы не разбирать её переходы заново на каждый день окна.
        Map<UUID, List<StatusPoint>> timelines = new HashMap<>();
        for (TaskRepository.TaskTimelineRow task : tasks) {
            timelines.put(task.getId(), statusPoints(task, transitions.get(task.getId())));
        }

        List<BurndownPoint> points = new ArrayList<>(days.size());
        for (int i = 0; i < days.size(); i++) {
            LocalDate day = days.get(i);
            Long remaining = day.isAfter(today) ? null : countRemaining(tasks, timelines, endOf(day));
            points.add(new BurndownPoint(day, remaining, ideal(tasks.size(), i, days.size())));
        }

        return new BurndownResponse(SprintSummary.from(sprint), sprint.getStartDate(), sprint.getEndDate(),
                tasks.size(), points);
    }

    /**
     * Сколько задач спринта было не закрыто к моменту {@code moment}. Задачи, заведённые
     * позже этого момента, не считаются вовсе: линия не должна начинаться с работы, которой
     * в тот день ещё не существовало.
     */
    private static long countRemaining(List<TaskRepository.TaskTimelineRow> tasks,
                                       Map<UUID, List<StatusPoint>> timelines, Instant moment) {
        long remaining = 0;
        for (TaskRepository.TaskTimelineRow task : tasks) {
            if (task.getCreatedAt().isAfter(moment)) {
                continue;
            }
            TaskStatus status = statusAt(timelines.get(task.getId()), moment);
            if (status != null && !CLOSED_STATUSES.contains(status)) {
                remaining++;
            }
        }
        return remaining;
    }

    private static TaskStatus statusAt(List<StatusPoint> timeline, Instant moment) {
        TaskStatus status = null;
        for (StatusPoint point : timeline) {
            if (point.at().isAfter(moment)) {
                break;
            }
            status = point.status();
        }
        return status;
    }

    /** Ряд «с какого момента какой статус»: создание задачи плюс каждый её переход. */
    private static List<StatusPoint> statusPoints(TaskRepository.TaskTimelineRow task,
                                                  List<ProjectActivityRepository.StatusTransition> taskTransitions) {
        List<StatusPoint> points = new ArrayList<>();
        points.add(new StatusPoint(task.getCreatedAt(), initialStatus(task, taskTransitions)));
        for (ProjectActivityRepository.StatusTransition transition : taskTransitions != null ? taskTransitions : List.<ProjectActivityRepository.StatusTransition>of()) {
            TaskStatus next = parseStatus(transition.getNewStatus());
            if (next != null) {
                points.add(new StatusPoint(transition.getChangedAt(), next));
            }
        }
        return points;
    }

    /**
     * Идеальная линия: от полного объёма в первый день до нуля в последний. У спринта из
     * одного дня (то есть у майлстоуна, см. 4.9) она вырождается в единственную точку с
     * нулём — «к этому дню должно быть сделано всё», что для майлстоуна ровно верно.
     */
    private static BigDecimal ideal(long scope, int dayIndex, int dayCount) {
        if (dayCount <= 1) {
            return BigDecimal.ZERO;
        }
        double value = scope * (1.0 - (double) dayIndex / (dayCount - 1));
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }

    private static List<LocalDate> daysOf(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            days.add(day);
        }
        return days;
    }

    /** Конец календарного дня — он же начало следующего: точка, на которую смотрит день. */
    private static Instant endOf(LocalDate day) {
        return day.plusDays(1).atStartOfDay(ZONE).toInstant();
    }

    private record StatusPoint(Instant at, TaskStatus status) {
    }
}
