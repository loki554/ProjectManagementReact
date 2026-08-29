package com.pmtracker.project_management_backend.project;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Кэш проверки «какая у пользователя роль в этом проекте» (3.10).
 * <p>
 * Проверка выполняется практически на каждом запросе к API и всегда представляет собой один
 * и тот же SELECT по (project_id, user_id). Кандидат на кэширование очевидный — и ровно
 * поэтому опасный: это авторизационное решение, и всякое кэширование добавляет окно, в
 * котором исключённый участник ещё считается участником.
 * <p>
 * Отсюда три решения.
 * <p>
 * Первое: <b>по умолчанию выключен</b>. IMPROVEMENTS.md помечает пункт P3 со словами «только
 * после того, как появятся реальные метрики», и это правильно — включать кэш авторизации,
 * не зная, сколько он экономит, значит платить риском неизвестно за что. Механизм готов и
 * проверен тестами, включение — операционное решение: {@code app.cache.membership.enabled}.
 * <p>
 * Второе: <b>явная инвалидация на каждое изменение состава участников</b>, а не только TTL.
 * TTL здесь страховка от того, что какое-то место забыли, а не основной механизм: исключение
 * из проекта должно действовать сразу, а не «в среднем через полминуты».
 * <p>
 * Третье: <b>инвалидация после коммита</b>. Сбросить запись внутри транзакции недостаточно —
 * параллельный запрос успеет прочитать ещё не изменённую строку и положить в кэш ту самую
 * роль, которую мы только что убрали. Именно из-за этого здесь Caffeine напрямую, а не
 * {@code @CacheEvict}: аннотация срабатывает по возврату из метода, то есть до коммита.
 */
@Component
public class ProjectMembershipCache {

    private record Key(UUID projectId, UUID userId) {
    }

    private final ProjectMemberRepository projectMemberRepository;

    /** null — кэш выключен, все обращения идут в базу. */
    private final Cache<Key, Optional<ProjectRole>> cache;

    public ProjectMembershipCache(ProjectMemberRepository projectMemberRepository,
                                  @Value("${app.cache.membership.enabled:false}") boolean enabled,
                                  @Value("${app.cache.membership.ttl:PT1M}") Duration ttl,
                                  // Потолок в записях, а не в байтах: запись — это два UUID
                                  // и enum. Десять тысяч пар «проект-пользователь» это
                                  // единицы мегабайт и заведомо больше, чем бывает активно
                                  // одновременно.
                                  @Value("${app.cache.membership.max-size:10000}") long maxSize) {
        this.projectMemberRepository = projectMemberRepository;
        this.cache = enabled
                ? Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maxSize).build()
                : null;
    }

    /**
     * Роль пользователя в проекте; пусто — не участник.
     *
     * <p>Отрицательный ответ кэшируется наравне с положительным: без этого запросы
     * постороннего к чужому проекту (в том числе перебором) ходили бы в базу каждый раз,
     * то есть кэш защищал бы ровно не тот случай. Приглашение нового участника этот
     * отрицательный ответ сбрасывает, см. {@link #invalidate}.
     */
    public Optional<ProjectRole> findRole(UUID projectId, UUID userId) {
        if (cache == null) {
            return projectMemberRepository.findRoleByProjectIdAndUserId(projectId, userId);
        }
        return cache.get(new Key(projectId, userId),
                key -> projectMemberRepository.findRoleByProjectIdAndUserId(key.projectId(), key.userId()));
    }

    /** Состав участников проекта изменился для одного человека: приглашение, смена роли, исключение. */
    public void invalidate(UUID projectId, UUID userId) {
        afterCommit(() -> {
            if (cache != null) {
                cache.invalidate(new Key(projectId, userId));
            }
        });
    }

    /** Проект удалён целиком — вместе с ним и все его членства. */
    public void invalidateProject(UUID projectId) {
        afterCommit(() -> {
            if (cache != null) {
                cache.asMap().keySet().removeIf(key -> key.projectId().equals(projectId));
            }
        });
    }

    /**
     * Откладывает сброс до коммита. Если транзакции нет (вызов из теста или из кода вне
     * транзакции), сбрасываем сразу — откатывать всё равно нечего. При откате транзакции
     * сброс тоже выполняется: лишняя инвалидация стоит одного SELECT, пропущенная — прав
     * доступа.
     */
    private void afterCommit(Runnable eviction) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            eviction.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                eviction.run();
            }
        });
    }
}
