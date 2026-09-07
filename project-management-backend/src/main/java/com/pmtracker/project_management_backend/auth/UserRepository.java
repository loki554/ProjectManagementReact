package com.pmtracker.project_management_backend.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Свободен ли никнейм. Отдельно от вставки, ради внятного 409 вместо 500 на нарушении
     * уникального индекса; сам индекс при этом никуда не девается и остаётся единственной
     * настоящей гарантией — между этой проверкой и вставкой помещается чужая регистрация
     * (см. AuthService.register).
     */
    boolean existsByUsername(String username);

    /** Занят ли никнейм кем-то, кроме этого пользователя, — проверка при смене в профиле. */
    boolean existsByUsernameAndIdNot(String username, UUID id);

    /**
     * Пути аватарок, на которые ссылаются пользователи — для сверки с диском (3.6).
     * Нативный запрос по той же причине, что и AttachmentRepository.findAllStoredPaths.
     */
    @Query(value = "select avatar_path from users where avatar_path is not null", nativeQuery = true)
    List<String> findAllAvatarPaths();
}
