package com.pmtracker.project_management_backend.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Суточная уборка просроченных приглашений (4.2).
 * <p>
 * Принятое приглашение удаляется в момент принятия, а вот непринятое не удаляется никогда:
 * человек может просто не прийти, и строка останется висеть навсегда. Сама по себе она
 * безвредна — просроченный токен не работает, — но занимает пару (проект, адрес) в
 * уникальном индексе и мозолит глаза в списке приглашений.
 * <p>
 * Джоб, как и остальные уборки, тупой и идемпотентный: никакого состояния, только «удалить
 * всё, что уже истекло». Поэтому лишний запуск (несколько инстансов, перезапуск) ничего не
 * портит, и распределённая блокировка ему не нужна — см. {@code SchedulerLock} о том, где
 * она действительно требуется.
 */
@Component
public class ProjectInvitationCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ProjectInvitationCleanupJob.class);

    private final ProjectInvitationRepository invitationRepository;

    public ProjectInvitationCleanupJob(ProjectInvitationRepository invitationRepository) {
        this.invitationRepository = invitationRepository;
    }

    // Своё окно: 3:30 — токены, 3:45 — корзина, 4:00 — файлы-сироты, 4:15 — уведомления,
    // 4:30 — приглашения.
    @Scheduled(cron = "${app.invitations.cleanup.cron:0 30 4 * * *}")
    @Transactional
    public void deleteExpiredInvitations() {
        int deleted = invitationRepository.deleteExpired(Instant.now());
        if (deleted > 0) {
            log.info("Invitation cleanup: deleted {} expired invitation(s)", deleted);
        }
    }
}
