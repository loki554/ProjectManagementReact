package com.pmtracker.project_management_backend.ratelimit;

import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Ограничивает частоту обращений к публичным auth-эндпоинтам. Без этого /login открыт для
 * перебора паролей, /resend-verification — для рассылки писем с нашего SMTP на чужие адреса,
 * /register — для забивания БД мусорными пользователями.
 *
 * Стоит в security-цепочке перед JwtAuthenticationFilter (см. SecurityConfig), то есть уже
 * после CorsFilter — важно, чтобы на ответе 429 были CORS-заголовки, иначе браузер покажет
 * фронтенду сетевую ошибку вместо внятного «слишком много попыток».
 *
 * Клиент определяется по request.getRemoteAddr(). За обратным прокси это будет адрес самого
 * прокси, то есть все пользователи склеятся в один ключ — при разворачивании за nginx/ingress
 * нужно включить server.forward-headers-strategy=framework, тогда ForwardedHeaderFilter
 * подставит сюда реальный адрес из X-Forwarded-For. Разбирать этот заголовок здесь
 * самостоятельно нельзя: клиент присылает его сам, и лимит обходился бы подделкой.
 */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);

    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String REGISTER_PATH = "/api/auth/register";
    private static final String RESEND_VERIFICATION_PATH = "/api/auth/resend-verification";
    private static final String REFRESH_PATH = "/api/auth/refresh";

    private static final Set<String> LIMITED_PATHS =
            Set.of(LOGIN_PATH, REGISTER_PATH, RESEND_VERIFICATION_PATH, REFRESH_PATH);

    /** Тела этих запросов — маленькие JSON-объекты; всё, что больше, разбору не подлежит. */
    private static final int MAX_INSPECTED_BODY_BYTES = 8 * 1024;

    /** Ключ для запроса, из тела которого email вытащить не удалось (битый JSON, пустое поле). */
    private static final String UNKNOWN_EMAIL = "-";

    private static final String TOO_MANY_REQUESTS_BODY =
            "{\"error\":\"TOO_MANY_REQUESTS\",\"message\":\"Too many requests. Please try again later.\"}";

    private final RateLimitService rateLimitService;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public AuthRateLimitFilter(RateLimitService rateLimitService,
                               RateLimitProperties properties,
                               ObjectMapper objectMapper) {
        this.rateLimitService = rateLimitService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !properties.isEnabled()
                || !HttpMethod.POST.matches(request.getMethod())
                || !LIMITED_PATHS.contains(pathWithinApplication(request));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String path = pathWithinApplication(request);
        String ip = request.getRemoteAddr();
        List<Check> checks = new ArrayList<>(2);
        HttpServletRequest downstreamRequest = request;

        switch (path) {
            case LOGIN_PATH -> {
                CachedBodyHttpServletRequest cached = cacheBody(request);
                downstreamRequest = cached == null ? request : cached;
                String email = cached == null ? UNKNOWN_EMAIL : extractEmail(cached.getCachedBody());
                checks.add(new Check("login:ip:" + ip, properties.getLoginPerIp()));
                checks.add(new Check("login:" + ip + "|" + email, properties.getLogin()));
            }
            case RESEND_VERIFICATION_PATH -> {
                CachedBodyHttpServletRequest cached = cacheBody(request);
                downstreamRequest = cached == null ? request : cached;
                String email = cached == null ? UNKNOWN_EMAIL : extractEmail(cached.getCachedBody());
                checks.add(new Check("resend:ip:" + ip, properties.getResendVerificationPerIp()));
                checks.add(new Check("resend:email:" + email, properties.getResendVerification()));
            }
            case REGISTER_PATH -> checks.add(new Check("register:ip:" + ip, properties.getRegister()));
            case REFRESH_PATH -> checks.add(new Check("refresh:ip:" + ip, properties.getRefresh()));
            default -> {
                // недостижимо: shouldNotFilter отсеял всё, кроме путей выше
            }
        }

        for (Check check : checks) {
            ConsumptionProbe probe = rateLimitService.tryConsume(check.key(), check.limit());
            if (!probe.isConsumed()) {
                rejectWithTooManyRequests(request, response, path, probe);
                return;
            }
        }

        filterChain.doFilter(downstreamRequest, response);
    }

    /**
     * @return null, если тело читать не стоит (слишком большое) — тогда запрос уходит дальше
     *         как есть, а лимит применяется только по IP.
     */
    private CachedBodyHttpServletRequest cacheBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_INSPECTED_BODY_BYTES) {
            return null;
        }
        return new CachedBodyHttpServletRequest(request);
    }

    /**
     * Email нормализуется к нижнему регистру, чтобы Bob@example.com и bob@example.com делили
     * один бакет — иначе лимит на аккаунт обходится сменой регистра.
     */
    private String extractEmail(byte[] body) {
        if (body.length == 0) {
            return UNKNOWN_EMAIL;
        }
        try {
            JsonNode email = objectMapper.readTree(body).path("email");
            if (!email.isString() || email.asString().isBlank()) {
                return UNKNOWN_EMAIL;
            }
            return email.asString().trim().toLowerCase(Locale.ROOT);
        } catch (JacksonException e) {
            // битый JSON — 400 отдаст уже MVC, нам достаточно посчитать попытку по IP
            return UNKNOWN_EMAIL;
        }
    }

    /**
     * Тело ответа статическое, поэтому собрано строкой — тот же приём и по той же причине,
     * что в RestAuthenticationEntryPoint. Контракт {error, message} такой же, как у остальных
     * ошибок; код TOO_MANY_REQUESTS переведён во фронтовых локалях.
     */
    private void rejectWithTooManyRequests(HttpServletRequest request, HttpServletResponse response,
                                           String path, ConsumptionProbe probe) throws IOException {
        long retryAfterSeconds = Math.max(1, secondsRoundedUp(probe.getNanosToWaitForRefill()));

        log.warn("Rate limit exceeded for {} from {}, retry after {}s", path, request.getRemoteAddr(), retryAfterSeconds);

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(TOO_MANY_REQUESTS_BODY);
    }

    private long secondsRoundedUp(long nanos) {
        long seconds = TimeUnit.NANOSECONDS.toSeconds(nanos);
        return TimeUnit.SECONDS.toNanos(seconds) == nanos ? seconds : seconds + 1;
    }

    private String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath.isEmpty() ? uri : uri.substring(contextPath.length());
    }

    private record Check(String key, RateLimitProperties.Limit limit) {
    }
}
