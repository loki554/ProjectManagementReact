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
     * Пути аватарок, на которые ссылаются пользователи — для сверки с диском (3.6).
     * Нативный запрос по той же причине, что и AttachmentRepository.findAllStoredPaths.
     */
    @Query(value = "select avatar_path from users where avatar_path is not null", nativeQuery = true)
    List<String> findAllAvatarPaths();
}
