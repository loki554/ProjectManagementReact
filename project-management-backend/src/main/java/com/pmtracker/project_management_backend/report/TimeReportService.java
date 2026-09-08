package com.pmtracker.project_management_backend.report;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.UserRepository;
import com.pmtracker.project_management_backend.auth.dto.UserSummary;
import com.pmtracker.project_management_backend.common.csv.CsvWriter;
import com.pmtracker.project_management_backend.common.exception.InvalidReportRangeException;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectAccessService;
import com.pmtracker.project_management_backend.report.dto.TimeReportDayRow;
import com.pmtracker.project_management_backend.report.dto.TimeReportResponse;
import com.pmtracker.project_management_backend.report.dto.TimeReportTaskRow;
import com.pmtracker.project_management_backend.report.dto.TimeReportUserRow;
import com.pmtracker.project_management_backend.timelog.TimeLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Отчёты по времени (4.10).
 *
 * <p><b>Зачем пункт вообще был.</b> Записи времени трекер собирал с V4, но сложить их умел
 * ровно одним способом — «сумма по этой задаче» (решение 6.7.3 плана). То есть данные
 * копились, а вопрос, ради которого их просят вводить («сколько ушло у команды за
 * сентябрь», «сколько на этом человеке»), оставался без ответа. Отчёт не заводит ни одной
 * новой таблицы и ни одного нового поля — он только складывает то, что уже лежит.
 *
 * <p><b>Про права.</b> Отчёт читает любой участник проекта, включая VIEWER, и это не
 * послабление: записи времени и так видны всем на карточке каждой задачи (см.
 * TimeLogService.list). Отчёт складывает ровно те же строки, ничего нового не показывая, —
 * закрывать сумму от того, кому доступны слагаемые, значило бы завести право, которое
 * обходится калькулятором.
 *
 * <p><b>Про период.</b> Границы включительные с обеих сторон: человек, который просит
 * «с 1 по 30 сентября», имеет в виду тридцать дней, а не двадцать девять. Без параметров
 * отдаются последние 30 дней — самый частый вопрос, ради которого отчёт открывают.
 */
@Service
public class TimeReportService {

    /** Период по умолчанию, включая сегодняшний день. */
    private static final int DEFAULT_PERIOD_DAYS = 30;

    /**
     * Потолок отчётного периода. Год — верхняя граница любого осмысленного «за период», а
     * без потолка выгрузка «за всё время» превращается в ответ неограниченного размера:
     * агрегаты-то маленькие, но CSV отдаёт сырые строки, и их столько, сколько их вообще
     * есть в проекте.
     */
    private static final int MAX_PERIOD_DAYS = 366;

    private static final List<String> CSV_HEADER = List.of(
            "Дата", "Участник", "Email", "Задача", "Название задачи", "Часы", "Описание");

    private final TimeLogRepository timeLogRepository;
    private final UserRepository userRepository;
    private final ProjectAccessService projectAccessService;

    public TimeReportService(TimeLogRepository timeLogRepository,
                             UserRepository userRepository,
                             ProjectAccessService projectAccessService) {
        this.timeLogRepository = timeLogRepository;
        this.userRepository = userRepository;
        this.projectAccessService = projectAccessService;
    }

    @Transactional(readOnly = true)
    public TimeReportResponse report(User currentUser, UUID projectId, LocalDate from, LocalDate to, UUID userId) {
        projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);

        Period period = resolvePeriod(from, to);

        List<TimeReportUserRow> byUser = loadByUser(projectId, period, userId);
        List<TimeReportTaskRow> byTask = timeLogRepository
                .sumHoursByTask(projectId, period.from(), period.to(), userId).stream()
                .map(row -> new TimeReportTaskRow(row.getTaskId(), row.getTaskNumber(), row.getTitle(),
                        row.getTotalHours()))
                .toList();

        // Итог складывается по разрезу участников, а не отдельным запросом: это те же
        // записи, и лишнее обращение к базе ради числа, которое уже посчитано, не нужно.
        BigDecimal totalHours = byUser.stream()
                .map(TimeReportUserRow::hours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new TimeReportResponse(period.from(), period.to(), userId, totalHours,
                byUser, byTask, loadByDay(projectId, period, userId));
    }

    /**
     * CSV-выгрузка тех же данных, но строками, а не суммами: сложить их по-своему — ровно
     * то, ради чего выгрузку и просят, и агрегаты этого не позволяют. Экран отвечает на
     * заранее известные вопросы, файл — на остальные.
     */
    @Transactional(readOnly = true)
    public TimeReportCsv exportCsv(User currentUser, UUID projectId, LocalDate from, LocalDate to, UUID userId) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);

        Period period = resolvePeriod(from, to);

        List<List<String>> rows = timeLogRepository
                .findRowsForExport(projectId, period.from(), period.to(), userId).stream()
                .map(row -> List.of(
                        row.getSpentOn().toString(),
                        fullName(row.getLastName(), row.getFirstName()),
                        row.getEmail(),
                        "#" + row.getTaskNumber(),
                        nullToEmpty(row.getTitle()),
                        // toPlainString, а не toString: у BigDecimal из NUMERIC(5,2)
                        // экспоненты не бывает, но полагаться на это в выгрузке незачем.
                        row.getHours().toPlainString(),
                        nullToEmpty(row.getDescription())))
                .toList();

        String filename = "time-report-%s-%s_%s.csv".formatted(project.getSlug(), period.from(), period.to());
        return new TimeReportCsv(filename, CsvWriter.write(CSV_HEADER, rows));
    }

    // ------------------------------------------------------------------------- внутреннее

    private List<TimeReportUserRow> loadByUser(UUID projectId, Period period, UUID userId) {
        List<TimeLogRepository.UserHoursTotal> totals =
                timeLogRepository.sumHoursByUser(projectId, period.from(), period.to(), userId);
        if (totals.isEmpty()) {
            return List.of();
        }

        // Люди догружаются одним запросом по собранным id: агрегат с group by не умеет
        // join fetch, а ходить за каждым по отдельности — это N+1 на ровном месте.
        Map<UUID, User> users = userRepository.findAllById(totals.stream()
                        .map(TimeLogRepository.UserHoursTotal::getUserId)
                        .toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        return totals.stream()
                .filter(total -> users.containsKey(total.getUserId()))
                .map(total -> new TimeReportUserRow(UserSummary.from(users.get(total.getUserId())),
                        total.getTotalHours(), total.getEntryCount()))
                // Больше всех часов — сверху; при равенстве по фамилии, чтобы порядок не
                // прыгал между запросами.
                .sorted(Comparator.comparing(TimeReportUserRow::hours).reversed()
                        .thenComparing(row -> nullToEmpty(row.user().lastName())))
                .toList();
    }

    /**
     * Дни периода с нулями на месте пустых. Нули подставляются здесь, а не в SQL
     * (generate_series умеет и это): полоса времени — это форма ответа, а не форма данных,
     * и решать, что выходной показывается нулём, должен тот, кто отвечает за смысл отчёта.
     */
    private List<TimeReportDayRow> loadByDay(UUID projectId, Period period, UUID userId) {
        Map<LocalDate, BigDecimal> hoursByDay = new HashMap<>();
        for (TimeLogRepository.DayHoursTotal total : timeLogRepository
                .sumHoursByDay(projectId, period.from(), period.to(), userId)) {
            hoursByDay.put(total.getDay(), total.getTotalHours());
        }

        List<TimeReportDayRow> days = new ArrayList<>();
        for (LocalDate day = period.from(); !day.isAfter(period.to()); day = day.plusDays(1)) {
            days.add(new TimeReportDayRow(day, hoursByDay.getOrDefault(day, BigDecimal.ZERO)));
        }
        return days;
    }

    /**
     * Границы периода. Пустые параметры — это «как обычно», а не ошибка: ссылка на отчёт
     * без query-параметров обязана открываться и показывать осмысленное.
     */
    private static Period resolvePeriod(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_PERIOD_DAYS - 1L);

        if (start.isAfter(end)) {
            throw new InvalidReportRangeException("Report period start cannot be after its end");
        }
        // +1, потому что границы включительные: с 1 по 1 января — это один день.
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_PERIOD_DAYS) {
            throw new InvalidReportRangeException("Report period cannot exceed " + MAX_PERIOD_DAYS + " days");
        }
        return new Period(start, end);
    }

    private static String fullName(String lastName, String firstName) {
        return (nullToEmpty(lastName) + " " + nullToEmpty(firstName)).trim();
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private record Period(LocalDate from, LocalDate to) {
    }
}
