package com.pmtracker.project_management_backend.project;

import com.pmtracker.project_management_backend.auth.EmailVerifiedEvent;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.UserRepository;
import com.pmtracker.project_management_backend.common.SecureTokens;
import com.pmtracker.project_management_backend.common.exception.InvalidOrExpiredTokenException;
import com.pmtracker.project_management_backend.common.exception.InvitationEmailMismatchException;
import com.pmtracker.project_management_backend.common.exception.ResourceNotFoundException;
import com.pmtracker.project_management_backend.mail.ProjectInvitationEmailRequestedEvent;
import com.pmtracker.project_management_backend.project.dto.AcceptedInvitationResponse;
import com.pmtracker.project_management_backend.project.dto.InvitationPreviewResponse;
import com.pmtracker.project_management_backend.project.dto.InvitationResponse;
import com.pmtracker.project_management_backend.project.dto.InviteMemberRequest;
import com.pmtracker.project_management_backend.project.dto.InviteResponse;
import com.pmtracker.project_management_backend.project.dto.MemberResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Приглашение в проект по email, включая тех, кто ещё не зарегистрирован (4.2).
 * <p>
 * До этого приглашение умело ровно одно: найти пользователя в {@code users} и добавить его
 * в проект. Не найдя — отдавало 404, и онбординг команды упирался в «сначала зарегистрируйся,
 * потом я тебя добавлю». Теперь у приглашения два исхода, и выбирает между ними сервер:
 * зарегистрированный становится участником сразу, незарегистрированному уходит письмо со
 * ссылкой, а строка в {@code project_invitations} ждёт, пока ею воспользуются.
 * <p>
 * Принять приглашение можно двумя путями, и это не дублирование, а два разных момента:
 * <ul>
 *   <li>{@link #accept} — человек уже вошёл в систему (аккаунт был или он завёл его между
 *       отправкой письма и переходом по ссылке) и жмёт «принять» своими руками;</li>
 *   <li>{@link #onEmailVerified} — аккаунта не было, человек зарегистрировался по ссылке и
 *       подтвердил адрес; подтверждение адреса и есть доказательство, что письмо с
 *       приглашением пришло именно ему, поэтому в проект он попадает автоматически.</li>
 * </ul>
 * Второй путь заодно закрывает случай «письмо потерялось, зарегистрировался сам по себе»:
 * приглашение всё равно сработает, как только адрес подтверждён.
 * <p>
 * Чего здесь сознательно нет — приёма приглашения прямо при регистрации, до подтверждения
 * адреса. Токен из письма доказывает лишь то, что письмо у предъявителя в руках; переслать
 * его ничего не стоит, и по пересланной ссылке в проект въезжал бы кто угодно. Подтверждение
 * адреса — то же самое доказательство, но полученное нашими руками, и стоит оно приглашённому
 * одного лишнего клика.
 */
@Service
public class ProjectInvitationService {

    private static final Logger log = LoggerFactory.getLogger(ProjectInvitationService.class);

    /**
     * Неделя. Против часа у сброса пароля и суток у подтверждения регистрации: там ссылку
     * ждёт человек, который только что сам нажал кнопку, здесь — тот, кто о приглашении не
     * просил и вполне может открыть почту в понедельник. Приглашение при этом не даёт доступа
     * ни к какому существующему аккаунту, то есть цена долгой жизни ссылки принципиально
     * ниже: максимум, что получает нашедший её, — один проект, и то лишь если владеет тем
     * самым почтовым ящиком (см. проверку адреса в {@link #accept}).
     */
    static final int INVITATION_TTL_DAYS = 7;

    /** 32 байта, 256 бит энтропии — как у ссылки сброса пароля. */
    private static final int INVITATION_TOKEN_BYTES = 32;

    private final ProjectInvitationRepository invitationRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectMemberService projectMemberService;
    private final ProjectAccessService projectAccessService;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ProjectInvitationService(ProjectInvitationRepository invitationRepository,
                                    ProjectMemberRepository projectMemberRepository,
                                    ProjectMemberService projectMemberService,
                                    ProjectAccessService projectAccessService,
                                    UserRepository userRepository,
                                    ApplicationEventPublisher eventPublisher) {
        this.invitationRepository = invitationRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.projectMemberService = projectMemberService;
        this.projectAccessService = projectAccessService;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
    }

    // ------------------------------------------------------------------ приглашение

    /**
     * Пригласить по email. OWNER/ADMIN, как и раньше.
     * <p>
     * Пользователь ищется по точному адресу — тем же {@code findByEmail}, что и при входе:
     * завести здесь регистронезависимый поиск значило бы, что «Ivan@example.com» и
     * «ivan@example.com» — один человек для приглашения и разные для логина. Промах по
     * регистру не ломает сценарий, а лишь уводит его в ветку приглашения: письмо придёт в
     * тот же ящик, а по ссылке человек войдёт под своим настоящим аккаунтом.
     * <p>
     * Повторное приглашение того же адреса — не вторая строка, а замена: у существующего
     * приглашения обновляются роль, токен и срок, и уходит новое письмо. Так «пригласить
     * ещё раз» работает заодно как «отправить ссылку повторно» и не оставляет за собой
     * веер рабочих ссылок в чужом почтовом ящике.
     */
    @Transactional
    public InviteResponse invite(User currentUser, UUID projectId, InviteMemberRequest request) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        ProjectRole myRole = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(myRole, ProjectRole.ADMIN);

        String email = normalizeEmail(request.email());
        Optional<User> registered = userRepository.findByEmail(request.email());

        if (registered.isPresent()) {
            MemberResponse member =
                    projectMemberService.addMember(project, registered.get(), request.role(), currentUser);
            // Приглашение на этот адрес могло висеть с тех пор, когда аккаунта ещё не было:
            // человек зарегистрировался, но по ссылке не сходил, а его позвали ещё раз.
            // Оставить строку значило бы оставить рабочую ссылку в проект, куда он уже принят.
            invitationRepository.findByProjectIdAndEmail(projectId, email)
                    .ifPresent(invitationRepository::delete);
            return InviteResponse.memberAdded(member);
        }

        String rawToken = SecureTokens.generate(INVITATION_TOKEN_BYTES);
        ProjectInvitation invitation = invitationRepository.findByProjectIdAndEmail(projectId, email)
                .orElseGet(() -> {
                    ProjectInvitation fresh = new ProjectInvitation();
                    fresh.setProject(project);
                    fresh.setEmail(email);
                    return fresh;
                });
        invitation.setRole(request.role());
        invitation.setTokenHash(SecureTokens.sha256Hex(rawToken));
        invitation.setInvitedBy(currentUser);
        invitation.setExpiresAt(Instant.now().plus(INVITATION_TTL_DAYS, ChronoUnit.DAYS));
        invitationRepository.save(invitation);

        // Письмо уходит после коммита и в отдельном потоке (см. MailDispatcher). Здесь это
        // важно вдвойне: ссылка не должна уехать раньше, чем токен окажется в БД.
        eventPublisher.publishEvent(new ProjectInvitationEmailRequestedEvent(
                email, rawToken, project.getName(), displayName(currentUser), INVITATION_TTL_DAYS));

        return InviteResponse.invitationSent(InvitationResponse.from(invitation, Instant.now()));
    }

    /**
     * Непринятые приглашения проекта. OWNER/ADMIN, а не любой участник, в отличие от списка
     * самих участников: это адреса людей, которые в проект ещё не вошли и, может быть, не
     * войдут, — знать их нужно тому, кто управляет составом, а не всем подряд.
     */
    @Transactional(readOnly = true)
    public List<InvitationResponse> list(User currentUser, UUID projectId) {
        projectAccessService.findProjectOrThrow(projectId);
        ProjectRole myRole = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(myRole, ProjectRole.ADMIN);

        Instant now = Instant.now();
        return invitationRepository.findByProjectIdWithInviter(projectId).stream()
                .map(invitation -> InvitationResponse.from(invitation, now))
                .toList();
    }

    /** Отозвать приглашение: строка удаляется, ссылка из письма перестаёт работать. */
    @Transactional
    public void revoke(User currentUser, UUID projectId, UUID invitationId) {
        projectAccessService.findProjectOrThrow(projectId);
        ProjectRole myRole = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(myRole, ProjectRole.ADMIN);

        ProjectInvitation invitation = invitationRepository.findById(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found"));
        // Приглашение адресуется парой (проект, id): без этой проверки ADMIN одного проекта
        // отзывал бы приглашения любого другого, зная только их id.
        if (!invitation.getProject().getId().equals(projectId)) {
            throw new ResourceNotFoundException("Invitation not found");
        }
        invitationRepository.delete(invitation);
    }

    // -------------------------------------------------------------------- принятие

    /**
     * Что показать тому, кто перешёл по ссылке. Публично и без аутентификации: решение
     * «заводить ли аккаунт ради этого проекта» человек принимает до того, как аккаунт у
     * него появится.
     * <p>
     * Просроченное приглашение неотличимо от несуществующего — оба дают INVALID_TOKEN.
     * Разделять их незачем: действие в обоих случаях одно и то же (попросить пригласить
     * заново), а «токен был, но протух» — лишний бит для того, кто ссылку подбирает.
     */
    @Transactional(readOnly = true)
    public InvitationPreviewResponse preview(String rawToken) {
        return InvitationPreviewResponse.from(findValidOrThrow(rawToken));
    }

    /**
     * Принять приглашение вошедшим пользователем.
     * <p>
     * Адрес аккаунта обязан совпадать с адресом приглашения — см.
     * {@link InvitationEmailMismatchException} о том, почему ссылка не работает как
     * предъявительский пропуск.
     * <p>
     * Повторный переход по уже использованной ссылке даёт INVALID_TOKEN (строки больше нет),
     * а вот приглашение в проект, участником которого пользователь успел стать другим путём,
     * ошибкой не считается: приглашение гасится, и человек получает свою уже существующую
     * строку участника. Иначе кнопка «принять» отдавала бы 409 тому, кто и так уже внутри.
     */
    @Transactional
    public AcceptedInvitationResponse accept(User currentUser, String rawToken) {
        ProjectInvitation invitation = findValidOrThrow(rawToken);

        if (!invitation.getEmail().equals(normalizeEmail(currentUser.getEmail()))) {
            throw new InvitationEmailMismatchException();
        }

        Project project = invitation.getProject();
        Optional<ProjectMember> existing =
                projectMemberRepository.findByProjectIdAndUserId(project.getId(), currentUser.getId());
        invitationRepository.delete(invitation);

        ProjectRole role = existing.map(ProjectMember::getRole).orElseGet(() -> {
            projectMemberService.addMember(project, currentUser, invitation.getRole(), currentUser);
            return invitation.getRole();
        });
        return AcceptedInvitationResponse.from(project, role);
    }

    /**
     * Подтверждение email — момент, когда приглашения, выписанные на адрес до появления
     * аккаунта, наконец находят своего владельца.
     * <p>
     * После коммита и в своей транзакции (REQUIRES_NEW): подтверждение адреса не должно
     * зависеть от того, как прошло добавление в проекты, — упавшее приглашение не имеет
     * права откатить верификацию и оставить человека с неработающим входом. Уже существующее
     * членство при этом не ошибка, а пропуск: строку приглашения всё равно нужно убрать.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onEmailVerified(EmailVerifiedEvent event) {
        List<ProjectInvitation> pending =
                invitationRepository.findPendingByEmail(normalizeEmail(event.email()), Instant.now());
        if (pending.isEmpty()) {
            return;
        }

        User user = userRepository.findById(event.userId()).orElse(null);
        if (user == null) {
            return;
        }

        int accepted = 0;
        for (ProjectInvitation invitation : pending) {
            UUID projectId = invitation.getProject().getId();
            // Участие проверяется до вызова, а не ловится исключением: addMember работает в
            // этой же транзакции, и вылетевшее из него AlreadyProjectMemberException пометило
            // бы её rollback-only — то есть перехват «ничего страшного» на деле уронил бы
            // весь разбор приглашений на коммите.
            if (projectMemberRepository.findByProjectIdAndUserId(projectId, user.getId()).isEmpty()) {
                projectMemberService.addMember(invitation.getProject(), user, invitation.getRole(), user);
                accepted++;
            }
            invitationRepository.delete(invitation);
        }
        log.info("User {} verified their email and joined {} project(s) by invitation",
                event.userId(), accepted);
    }

    // ------------------------------------------------------------------ внутреннее

    private ProjectInvitation findValidOrThrow(String rawToken) {
        ProjectInvitation invitation = invitationRepository.findByTokenHash(SecureTokens.sha256Hex(rawToken))
                .orElseThrow(InvalidOrExpiredTokenException::new);
        if (invitation.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidOrExpiredTokenException();
        }
        return invitation;
    }

    /**
     * Приглашение выписывает один человек, а предъявляет другой, и адрес они наберут
     * по-разному. Хранить и сравнивать в нижнем регистре — единственный способ сделать так,
     * чтобы «Ivan@Example.com» в форме приглашения и «ivan@example.com» при регистрации
     * оказались одним адресом. ROOT, а не локаль по умолчанию: в турецкой «I» становится «ı».
     */
    private static String normalizeEmail(String email) {
        return email.toLowerCase(Locale.ROOT);
    }

    private static String displayName(User user) {
        return user.getLastName() + " " + user.getFirstName();
    }
}
