package com.pmtracker.project_management_backend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Без этого бина Spring Security по умолчанию отвечает 403 Forbidden на любой запрос
 * без валидной аутентификации, что путает "не залогинен" с "залогинен, но нет прав".
 * Отдаём 401, чтобы фронтенд мог однозначно отличить эти случаи и понять, когда стоит
 * попробовать обновить access-токен через /api/auth/refresh.
 *
 * Тело статическое и не зависит от пользовательского ввода, поэтому JSON собран руками —
 * не тянем Jackson ObjectMapper в compile-classpath ради одного факта.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String BODY =
            "{\"error\":\"UNAUTHENTICATED\",\"message\":\"Authentication required\"}";

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(BODY);
    }
}
