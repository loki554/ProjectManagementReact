package com.pmtracker.project_management_backend.config;

import com.pmtracker.project_management_backend.auth.JwtService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Читает "Authorization: Bearer <access-token>", проверяет подпись/срок годности
 * через JwtService и, если всё ок, кладёт пользователя в SecurityContext.
 * Если токена нет или он невалиден — просто пропускает запрос дальше без аутентификации;
 * дальше уже SecurityConfig решает, требовать её для этого пути или нет (см. .anyRequest().authenticated()).
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length());
            try {
                UUID userId = jwtService.extractUserId(token);
                userRepository.findById(userId).ifPresent(this::authenticate);
            } catch (JwtException | IllegalArgumentException e) {
                // невалидный/просроченный токен — оставляем запрос неаутентифицированным
            }
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(User user) {
        var authentication = new UsernamePasswordAuthenticationToken(user, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
