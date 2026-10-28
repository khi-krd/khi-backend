package ak.dev.khi_backend.khi_app.exceptions;

public enum ErrorCode {
    // ── Validation ────────────────────────────────────────────────────────────
    VALIDATION_ERROR,       // @Valid / @Validated constraint failed
    MISSING_PARAMETER,      // Required @RequestParam absent

    // ── User / Auth ───────────────────────────────────────────────────────────
    NOT_FOUND,              // Resource does not exist
    UNAUTHORIZED,           // Wrong credentials
    FORBIDDEN,              // Authenticated but insufficient role/permission
    ACCOUNT_LOCKED,         // Too many failed login attempts
    CONFLICT,               // Duplicate username/email or DB unique violation

    // ── HTTP / Request ────────────────────────────────────────────────────────
    BAD_REQUEST,            // Malformed input, invalid argument, illegal state
    METHOD_NOT_ALLOWED,     // Wrong HTTP verb for the endpoint
    PAYLOAD_TOO_LARGE,      // File upload exceeds size limit
    MULTIPART_ERROR,        // Multipart request malformed or a required part absent

    // ── Project ───────────────────────────────────────────────────────────────
    PROJECT_NOT_FOUND,      // Project with given ID does not exist
    PROJECT_CONFLICT,       // Duplicate project data (unique-constraint violation)
    PROJECT_VALIDATION,     // Project field-level validation failure
    PROJECT_MEDIA_INVALID,  // Invalid or incomplete project media data

    // ── News ────────────────────────────────────────────────────────────────
    NEWS_NOT_FOUND,
    NEWS_CONFLICT,
    NEWS_VALIDATION,
    NEWS_MEDIA_INVALID,

    // ── Video (publishment) ──────────────────────────────────────────────
    VIDEO_NOT_FOUND,
    VIDEO_CONFLICT,
    VIDEO_VALIDATION,
    VIDEO_MEDIA_INVALID,

    // ── Image (publishment) ───────────────────────────────────────────────
    IMAGE_NOT_FOUND,
    IMAGE_CONFLICT,
    IMAGE_VALIDATION,
    IMAGE_MEDIA_INVALID,

    // ── Sound (publishment) ───────────────────────────────────────────────
    SOUND_NOT_FOUND,
    SOUND_CONFLICT,
    SOUND_VALIDATION,
    SOUND_MEDIA_INVALID,

    // ── Writing (publishment) ─────────────────────────────────────────────
    WRITING_NOT_FOUND,
    WRITING_CONFLICT,
    WRITING_VALIDATION,
    WRITING_MEDIA_INVALID,

    // ── Infrastructure ────────────────────────────────────────────────────────
    DB_ERROR,               // Database-level error
    STORAGE_ERROR,          // S3 / file storage error
    EXTERNAL_ERROR,         // Third-party service error
    INTERNAL_ERROR          // Catch-all unexpected server error
}
