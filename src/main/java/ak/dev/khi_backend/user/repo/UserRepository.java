package ak.dev.khi_backend.user.repo;

import ak.dev.khi_backend.user.enums.Role;
import ak.dev.khi_backend.user.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);

    // ── 2-Phase Search ────────────────────────────────────────────────────────

    /**
     * Phase 1 — Exact match on username OR email in a SINGLE database query.
     * Hits the unique indexes on both columns → optimal performance.
     * Used as the primary lookup for login and password reset.
     */
    @Query("SELECT u FROM User u WHERE u.username = :identifier OR u.email = :identifier")
    Optional<User> findByUsernameOrEmailExact(@Param("identifier") String identifier);

    /**
     * Phase 2 — Case-insensitive fallback.
     * Runs only when Phase 1 finds nothing (e.g. user typed "AKAR@GMAIL.COM"
     * but the stored email is "akar@gmail.com").
     * Uses LOWER() so it works on every RDBMS (MySQL, PostgreSQL, H2).
     */
    @Query("SELECT u FROM User u WHERE LOWER(u.username) = LOWER(:identifier) OR LOWER(u.email) = LOWER(:identifier)")
    Optional<User> findByUsernameOrEmailIgnoreCase(@Param("identifier") String identifier);

    /** Admin user-management search: keyword across name/username/email, optional role filter. */
    @Query("SELECT u FROM User u WHERE (:keyword IS NULL OR :keyword = '' "
            + "OR LOWER(u.name) LIKE LOWER(CONCAT('%', :keyword, '%')) "
            + "OR LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%')) "
            + "OR LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%'))) "
            + "AND (:role IS NULL OR u.role = :role)")
    Page<User> searchUsers(@Param("keyword") String keyword,
                           @Param("role") Role role,
                           Pageable pageable);

    long countByRole(Role role);
}
