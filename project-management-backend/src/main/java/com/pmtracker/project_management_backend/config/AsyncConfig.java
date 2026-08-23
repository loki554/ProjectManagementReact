package com.pmtracker.project_management_backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Пул для фоновой отправки почты (см. VerificationMailDispatcher). Отдельный, а не общий
 * SimpleAsyncTaskExecutor Spring'а по умолчанию: тот на каждую задачу заводит новый поток,
 * и лежащий SMTP при всплеске регистраций превратился бы в неограниченный рост числа потоков.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    public static final String MAIL_EXECUTOR = "mailExecutor";

    @Bean(MAIL_EXECUTOR)
    public Executor mailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        // Очередь ограничена сознательно: письмо — вещь не настолько ценная, чтобы ради неё
        // копить в памяти неограниченную очередь, пока SMTP лежит.
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("mail-");
        // Переполнение очереди только логируем и роняем задачу. Ни AbortPolicy, ни CallerRuns
        // здесь не годятся: submit происходит в afterCommit-колбэке на потоке HTTP-запроса,
        // то есть исключение оттуда прилетело бы клиенту 500-й уже ПОСЛЕ успешной регистрации,
        // а CallerRuns заставил бы поток запроса самому идти в SMTP — ровно то, от чего уходим.
        executor.setRejectedExecutionHandler((task, rejectingExecutor) ->
                log.error("Mail executor queue is full, dropping an outgoing email task"));
        // Не терять письма, уже стоящие в очереди, при штатной остановке приложения.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }
}
