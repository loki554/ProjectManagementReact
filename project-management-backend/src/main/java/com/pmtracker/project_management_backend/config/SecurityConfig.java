package com.pmtracker.project_management_backend.config;

import com.pmtracker.project_management_backend.ratelimit.AuthRateLimitFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Ни API, ни SPA не пользуются ни одной из этих возможностей браузера, поэтому забираем
     * их у страницы целиком: даже успешная XSS не сможет попросить камеру или геолокацию.
     */
    private static final String PERMISSIONS_POLICY =
            "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), "
                    + "microphone=(), payment=(), usb=()";

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AuthRateLimitFilter authRateLimitFilter;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    /**
     * Значение по умолчанию (строгое, для JSON-API) лежит в application.properties, dev
     * ослабляет его в application-dev.yml ради Swagger UI. Property, а не константа, именно
     * поэтому: единственная страница, которой нужен послабленный CSP, живёт только в dev.
     */
    @Value("${app.security.content-security-policy}")
    private String contentSecurityPolicy;

    /**
     * Тот же выключатель, что гасит сам springdoc (по умолчанию выключен, включён только
     * в dev — см. application.properties). Держим публичный доступ к путям документации на
     * нём же, чтобы не разъехалось: где спеки нет, там и permitAll на неё не нужен.
     */
    @Value("${springdoc.api-docs.enabled:false}")
    private boolean apiDocsEnabled;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                           AuthRateLimitFilter authRateLimitFilter,
                           RestAuthenticationEntryPoint restAuthenticationEntryPoint) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authRateLimitFilter = authRateLimitFilter;
        this.restAuthenticationEntryPoint = restAuthenticationEntryPoint;
    }

    /**
     * Boot регистрирует КАЖДЫЙ бин типа Filter ещё и в общей цепочке сервлет-контейнера,
     * поэтому фильтр, добавленный в security-цепочку через addFilterBefore, без этого
     * отрабатывал бы дважды за запрос — для лимитера это означало бы двойной расход
     * токенов и фактически вдвое меньший лимит. Отключаем авторегистрацию и оставляем
     * ровно одно вхождение — внутри security-цепочки (где на ответе уже есть CORS-заголовки).
     */
    @Bean
    public FilterRegistrationBean<AuthRateLimitFilter> authRateLimitFilterRegistration(AuthRateLimitFilter filter) {
        FilterRegistrationBean<AuthRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Фронтенд (localhost:5173 в dev) и бэкенд (localhost:8080) — разные origin
     * с точки зрения браузера, поэтому без явного разрешения CORS браузер режет
     * запросы на препрод-этапе (preflight OPTIONS), даже если сам эндпоинт публичный.
     * curl/Postman эту проверку не делают — она специфична для браузеров, поэтому
     * в тестах через curl (1.1, 1.2) эта проблема не проявлялась.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(frontendBaseUrl));
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // Браузер по умолчанию не даёт JS читать никакие заголовки ответа, кроме нескольких
        // «безопасных», — без этой строки фронтенд не увидит Retry-After у ответа 429
        // (см. AuthRateLimitFilter) и не сможет сказать пользователю, когда пробовать снова.
        config.setExposedHeaders(List.of("Retry-After"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // X-Content-Type-Options: nosniff и X-Frame-Options: DENY Spring Security ставит
                // сам, здесь — то, чего он по умолчанию не даёт.
                .headers(headers -> headers
                        // Второй рубеж под rehype-sanitize: приложение рендерит пользовательский
                        // Markdown (описания задач, комментарии), и если в санитайзере найдут дыру,
                        // строгий CSP не даст выполнить то, что через неё пролезло. Для JSON-API
                        // это бесплатно — ему не нужен вообще ни один внешний ресурс.
                        .contentSecurityPolicy(csp -> csp.policyDirectives(contentSecurityPolicy))
                        // Ссылки на бэкенд содержат id проектов/задач в пути — в Referer чужому
                        // сайту им попадать незачем.
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .permissionsPolicyHeader(permissions -> permissions.policy(PERMISSIONS_POLICY))
                        // Год + поддомены — значение, с которым принимают в preload-список.
                        // ВАЖНО: Spring Security пишет HSTS только на запросы, которые сам считает
                        // защищёнными (request.isSecure()). За TLS-терминирующим прокси это ложь,
                        // пока не включён server.forward-headers-strategy — см. комментарий в
                        // application-prod.yml, без него заголовок в проде просто не появится.
                        .httpStrictTransportSecurity(hsts -> hsts
                                .maxAgeInSeconds(Duration.ofDays(365).toSeconds())
                                .includeSubDomains(true)))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(
                            "/api/auth/register",
                            "/api/auth/verify-email",
                            "/api/auth/resend-verification",
                            "/api/auth/login",
                            "/api/auth/refresh",
                            "/api/auth/logout"
                    ).permitAll();
                    // Swagger UI/OpenAPI-спека не содержит чувствительных данных, но это полная
                    // карта API — читать её анонимно даём только там, где сама документация
                    // включена, то есть в dev. В проде springdoc выключен целиком, и эти пути
                    // попадают под anyRequest().authenticated() — то есть отдают 401, а не карту.
                    if (apiDocsEnabled) {
                        auth.requestMatchers(
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll();
                    }
                    // Без этого Boot форвардит ЛЮБОЕ необработанное исключение (даже уже
                    // корректно разрешённое в 400 через DefaultHandlerExceptionResolver,
                    // например HttpMessageNotReadableException на публичном /api/auth/login)
                    // на /error, а этот forward заново проходит через security filter chain.
                    // Т.к. /error не был в permitAll, анонимный forward ловил
                    // .anyRequest().authenticated() и подменял настоящий статус/тело ответа
                    // на 401 UNAUTHENTICATED — даже для уже аутентифицированных запросов
                    // (см. Phase 8, найдено при аудите единого обработчика ошибок).
                    auth.requestMatchers("/error").permitAll();
                    auth.anyRequest().authenticated();
                })
                .exceptionHandling(ex -> ex.authenticationEntryPoint(restAuthenticationEntryPoint))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Лимитер стоит перед JwtAuthenticationFilter: смысл в том, чтобы отсеять
                // шквал запросов раньше, чем они дойдут до БД и BCrypt (см. AuthRateLimitFilter).
                // Порядок этих двух вызовов важен: addFilterBefore умеет позиционировать
                // фильтр относительно другого кастомного только после того, как тот сам
                // зарегистрирован в цепочке, иначе конфигурация падает на старте.
                .addFilterBefore(authRateLimitFilter, JwtAuthenticationFilter.class);
        return http.build();
    }
}
