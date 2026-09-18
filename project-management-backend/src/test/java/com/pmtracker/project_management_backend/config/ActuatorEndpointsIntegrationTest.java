package com.pmtracker.project_management_backend.config;

import com.pmtracker.project_management_backend.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Actuator: что открыто наружу без токена и что не открыто (8.6 IMPROVEMENTS.md).
 * <p>
 * Тест нужен именно потому, что здесь легко ошибиться в безопасную на вид сторону. Health
 * обязан отвечать анонимно — проба docker/оркестратора ходит без учётных данных, и инстанс,
 * отвечающий ей 401, для балансировщика неотличим от мёртвого. А всё остальное, что приносит
 * actuator, анонимным быть не должно, и разъехаться эти два требования могут тихо: достаточно
 * чьей-нибудь строчки {@code management.endpoints.web.exposure.include=*} или {@code permitAll}
 * на {@code /actuator/**}, и снаружи окажется {@code /actuator/env} с паролем БД и JWT-секретом.
 * <p>
 * Профиль {@code test} настройки actuator не переопределяет, то есть проверяются значения из
 * {@code application.properties} — те же, с которыми поедет прод. Разбор health по компонентам
 * включает только dev, и отсутствие {@code components} в ответе проверяется здесь явно.
 */
class ActuatorEndpointsIntegrationTest extends IntegrationTest {

    @Test
    @DisplayName("/actuator/health открыт без токена и отдаёт только статус")
    void healthIsPublicAndOpaque() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                // Карта инфраструктуры (какая БД, сколько места на диске, какие пути) наружу
                // не уходит: show-details/show-components = never везде, кроме dev.
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    @DisplayName("liveness не зависит от БД, readiness — зависит")
    void livenessAndReadinessAskDifferentQuestions() throws Exception {
        // Разделение не косметическое: красная liveness означает «перезапусти контейнер», и
        // включать в неё БД значило бы уводить приложение в CrashLoopBackOff каждый раз, когда
        // недоступна чужая база. Поэтому в liveness — только состояние самого приложения, и
        // единственный компонент, который тут вообще может быть, это livenessState.
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        // Readiness отвечает на «можно слать трафик», и БД сюда добавлена сознательно: без неё
        // каждый запрос всё равно закончится 500. Контейнер Postgres в тестах поднят, так что
        // здесь проверяется не «UP», а то, что группа собрана и db в неё входит, — иначе строка
        // management.endpoint.health.group.readiness.include может тихо перестать действовать.
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator",
            "/actuator/info",
            // Три самых дорогих в случае утечки: env и configprops печатают конфигурацию
            // целиком (включая DB_PASSWORD и JWT_SECRET), mappings — полную карту API,
            // ради которой в 1.4 закрывали Swagger.
            "/actuator/env",
            "/actuator/configprops",
            "/actuator/mappings",
            "/actuator/beans",
            "/actuator/loggers",
            "/actuator/heapdump",
            "/actuator/threaddump",
            "/actuator/metrics"
    })
    @DisplayName("всё, кроме health, анонимному клиенту недоступно")
    void everythingElseIsClosed(String path) throws Exception {
        // 401, а не 404: анонимный клиент не должен по ответу понять, какие эндпоинты
        // вообще включены. Не-выставленные эндпоинты отдают 404 уже после аутентификации.
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }
}
