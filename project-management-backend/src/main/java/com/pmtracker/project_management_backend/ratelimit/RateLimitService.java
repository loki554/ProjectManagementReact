package com.pmtracker.project_management_backend.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Хранилище token-bucket'ов в памяти процесса (bucket4j). Этого достаточно, пока инстанс
 * один; при горизонтальном масштабировании ConcurrentHashMap заменяется на bucket4j-redis
 * (ProxyManager) — API вызова tryConsume при этом не меняется.
 *
 * Бакеты создаются лениво по ключу и удаляются, когда по ключу давно не было запросов:
 * ключей потенциально столько же, сколько разных IP/email обратилось к сервису, и без
 * вытеснения это утечка памяти.
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    /**
     * Потолок на число одновременно отслеживаемых ключей. Сам по себе он не должен
     * достигаться (лимиты на IP ограничивают скорость появления новых ключей), поэтому
     * достижение потолка — сигнал в лог, а не штатный режим.
     */
    private static final int MAX_TRACKED_KEYS = 100_000;

    private final Map<String, TrackedBucket> buckets = new ConcurrentHashMap<>();

    /**
     * @return результат попытки: {@link ConsumptionProbe#isConsumed()} == false означает,
     *         что лимит исчерпан, а getNanosToWaitForRefill() — сколько ждать до следующей попытки.
     */
    public ConsumptionProbe tryConsume(String key, RateLimitProperties.Limit limit) {
        if (buckets.size() >= MAX_TRACKED_KEYS && !buckets.containsKey(key)) {
            evictIdleBuckets();
            if (buckets.size() >= MAX_TRACKED_KEYS) {
                log.warn("Rate limit bucket registry is full ({} keys), not tracking new key", MAX_TRACKED_KEYS);
                return ConsumptionProbe.consumed(0, 0);
            }
        }

        TrackedBucket tracked = buckets.computeIfAbsent(key, ignored -> new TrackedBucket(newBucket(limit), idleTtl(limit)));
        tracked.lastAccess = Instant.now();
        return tracked.bucket.tryConsumeAndReturnRemaining(1);
    }

    /**
     * Бакет отдаёт capacity токенов, после чего они целиком возвращаются разом через period
     * (refillIntervally, а не refillGreedy) — это привычное пользователю поведение
     * "N попыток за X минут, потом ждём X минут", а не "по одной попытке каждые X/N минут".
     */
    private Bucket newBucket(RateLimitProperties.Limit limit) {
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(limit.getCapacity())
                .refillIntervally(limit.getCapacity(), limit.getPeriod())
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }

    /**
     * Держим бакет ещё один период после последнего обращения: удалить его раньше — значит
     * выдать нарушителю полный запас токенов заново, просто выждав паузу.
     */
    private Duration idleTtl(RateLimitProperties.Limit limit) {
        return limit.getPeriod().multipliedBy(2);
    }

    @Scheduled(fixedDelay = 10, initialDelay = 10, timeUnit = TimeUnit.MINUTES)
    void evictIdleBuckets() {
        Instant now = Instant.now();
        int before = buckets.size();
        buckets.entrySet().removeIf(entry -> entry.getValue().isIdleSince(now));
        int removed = before - buckets.size();
        if (removed > 0) {
            log.debug("Evicted {} idle rate limit buckets, {} left", removed, buckets.size());
        }
    }

    private static final class TrackedBucket {

        private final Bucket bucket;
        private final Duration idleTtl;
        private volatile Instant lastAccess = Instant.now();

        private TrackedBucket(Bucket bucket, Duration idleTtl) {
            this.bucket = bucket;
            this.idleTtl = idleTtl;
        }

        private boolean isIdleSince(Instant now) {
            return lastAccess.plus(idleTtl).isBefore(now);
        }
    }
}
