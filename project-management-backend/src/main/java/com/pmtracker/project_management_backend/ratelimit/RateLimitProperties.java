package com.pmtracker.project_management_backend.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Лимиты для {@link AuthRateLimitFilter}. Значения по умолчанию заданы прямо здесь, а не в
 * yml, чтобы любой профиль (в том числе будущий prod, у которого своего файла ещё нет)
 * получал защиту без дополнительной настройки. Переопределяются через app.rate-limit.*
 *
 * Период задаётся в формате Spring Duration ("15m", "1h").
 */
@Component
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    /** Полное отключение — только для локальной отладки/тестов. */
    private boolean enabled = true;

    /** Попытки входа на пару (IP, email): защищает конкретный аккаунт от перебора пароля. */
    private Limit login = new Limit(5, Duration.ofMinutes(15));

    /**
     * Попытки входа на один IP независимо от email. Нужен вторым рубежом: без него
     * атакующий с одного адреса перебирает произвольные email'ы, каждый из которых
     * заводит собственный бакет — то есть обходит лимит выше и заодно раздувает
     * таблицу бакетов в памяти.
     */
    private Limit loginPerIp = new Limit(20, Duration.ofMinutes(15));

    /** Регистрации на IP: против забивания БД мусорными пользователями. */
    private Limit register = new Limit(20, Duration.ofHours(1));

    /** Письма с подтверждением на один email: каждый вызов — письмо на чужой адрес. */
    private Limit resendVerification = new Limit(3, Duration.ofHours(1));

    /** То же на IP — по той же причине, что и loginPerIp (ключ здесь — email из тела). */
    private Limit resendVerificationPerIp = new Limit(10, Duration.ofHours(1));

    /**
     * Обновление токена на IP. Лимит намеренно щедрый: в норме это один запрос на сессию
     * раз в 15 минут (TTL access-токена), но за NAT/корпоративным прокси за одним адресом
     * могут сидеть десятки живых пользователей, а 429 здесь для фронтенда равносилен
     * протухшей сессии (см. интерсептор в client.js) — то есть ложное срабатывание
     * выкидывает человека на /login.
     */
    private Limit refresh = new Limit(60, Duration.ofMinutes(15));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Limit getLogin() {
        return login;
    }

    public void setLogin(Limit login) {
        this.login = login;
    }

    public Limit getLoginPerIp() {
        return loginPerIp;
    }

    public void setLoginPerIp(Limit loginPerIp) {
        this.loginPerIp = loginPerIp;
    }

    public Limit getRegister() {
        return register;
    }

    public void setRegister(Limit register) {
        this.register = register;
    }

    public Limit getResendVerification() {
        return resendVerification;
    }

    public void setResendVerification(Limit resendVerification) {
        this.resendVerification = resendVerification;
    }

    public Limit getResendVerificationPerIp() {
        return resendVerificationPerIp;
    }

    public void setResendVerificationPerIp(Limit resendVerificationPerIp) {
        this.resendVerificationPerIp = resendVerificationPerIp;
    }

    public Limit getRefresh() {
        return refresh;
    }

    public void setRefresh(Limit refresh) {
        this.refresh = refresh;
    }

    public static class Limit {

        /** Сколько запросов разрешено за период. */
        private int capacity;

        /** За какое время расходуется и целиком восстанавливается capacity. */
        private Duration period;

        public Limit() {
        }

        public Limit(int capacity, Duration period) {
            this.capacity = capacity;
            this.period = period;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public Duration getPeriod() {
            return period;
        }

        public void setPeriod(Duration period) {
            this.period = period;
        }
    }
}
