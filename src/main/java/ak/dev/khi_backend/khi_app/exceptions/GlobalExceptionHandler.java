package ak.dev.khi_backend.khi_app.exceptions;
import ak.dev.khi_backend.khi_app.config.TraceIdFilter;
import ak.dev.khi_backend.user.consts.SecurityConstants;
import ak.dev.khi_backend.user.exceptions.UserAlreadyExistsException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {
    /** Kurdish locale (use forLanguageTag to avoid deprecated new Locale(String)) */
    private static final Locale LOCALE_KU        = Locale.forLanguageTag("ku");
    private static final long   MAX_UPLOAD_MB    = 5L;
    private final MessageSource messageSource;
    // ═══════════════════════════════════════════════════════════════════════════
    // khi_app Domain Exceptions
    // ═══════════════════════════════════════════════════════════════════════════
    /**
     * Handles all structured domain exceptions thrown via {@link Errors} factory or
     * subclasses ({@link BadRequestException}, {@link ConflictException}, etc.)
     */
    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiErrorResponse> handleApp(
            AppException ex, HttpServletRequest req, Locale locale) {
        ApiErrorResponse body = base(req, ex.getHttpStatus().value(), ex.getCode());
        body.setMessage  (resolve(locale,         ex.getMessageKey(), ex.getMessageArgs(), fallbackByCode(ex.getCode(), "en")));
        body.setMessageEn(resolve(Locale.ENGLISH, ex.getMessageKey(), ex.getMessageArgs(), fallbackByCode(ex.getCode(), "en")));
        body.setMessageKu(resolve(LOCALE_KU,      ex.getMessageKey(), ex.getMessageArgs(), fallbackByCode(ex.getCode(), "ku")));
        body.setDetails(ex.getDetails());
        log.warn("AppException code={} messageKey={} status={} path={} traceId={} details={}",
                ex.getCode(), ex.getMessageKey(), ex.getHttpStatus().value(),
                req.getRequestURI(), body.getTraceId(), ex.getDetails());
        return ResponseEntity.status(ex.getHttpStatus()).body(body);
    }
    // ═══════════════════════════════════════════════════════════════════════════
    // USER / AUTH Exceptions
    // ═══════════════════════════════════════════════════════════════════════════
    /**
     * 409 CONFLICT — Username or email is already taken during registration or
     * profile update.
     *
     * details:
     *   hint       → suggest choosing a different value
     *   suggestion → remind user they may already have an account
     */
    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleUserAlreadyExists(
            UserAlreadyExistsException ex, HttpServletRequest req, Locale locale) {
        ApiErrorResponse body = base(req, 409, ErrorCode.CONFLICT);
        body.setMessage  (resolve(locale,         "error.user.already_exists", null, ex.getMessage()));
        body.setMessageEn(resolve(Locale.ENGLISH, "error.user.already_exists", null, ex.getMessage()));
        body.setMessageKu(resolve(LOCALE_KU,      "error.user.already_exists", null, ex.getMessage()));
        body.setDetails(Map.of(
                "hint",       "Try a different username or email address.",
                "suggestion", "If this is your account, try logging in or use the 'Forgot password' option."
        ));
        log.warn("UserAlreadyExists path={} traceId={} msg={}", req.getRequestURI(), body.getTraceId(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
    /**
     * 401 UNAUTHORIZED — Wrong password supplied.
     *
     * NOTE: When thrown from the login flow, remaining-attempts info is embedded
     * directly in the Token.response field by UserService.login() (which intercepts
     * the flow before throwing). This handler covers other callers.
     *
     * details:
     *   hint → remind user about the forgot-password option
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleBadCredentials(
            BadCredentialsException ex, HttpServletRequest req, Locale locale) {
        ApiErrorResponse body = base(req, 401, ErrorCode.UNAUTHORIZED);
        body.setMessage  (resolve(locale,         "error.user.bad_credentials", null, "Invalid username or password."));
        body.setMessageEn(resolve(Locale.ENGLISH, "error.user.bad_credentials", null, "Invalid username or password."));
        body.setMessageKu(resolve(LOCALE_KU,      "error.user.bad_credentials", null, "ناوی بەکارهێنەر یان وشەی نهێنی هەڵەیە."));
        body.setDetails(Map.of(
                "hint", "Double-check your username/email and password. Use 'Forgot password' if you cannot remember it."
        ));
        log.warn("BadCredentials path={} traceId={}", req.getRequestURI(), body.getTraceId());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }
    /**
     * 423 LOCKED — Account is temporarily locked after too many failed login attempts.
     *
     * details:
     *   maxAttempts         → threshold that triggered the lock
     *   lockDurationMinutes → how long the lock lasts
     *   hint                → actionable guidance (wait or reset password)
     */
    @ExceptionHandler(LockedException.class)
    public ResponseEntity<ApiErrorResponse> handleLocked(
            LockedException ex, HttpServletRequest req, Locale locale) {
        ApiErrorResponse body = base(req, 423, ErrorCode.ACCOUNT_LOCKED);
        body.setMessage  (resolve(locale,         "error.user.locked", null, ex.getMessage()));
        body.setMessageEn(resolve(Locale.ENGLISH, "error.user.locked", null, ex.getMessage()));
        body.setMessageKu(resolve(LOCALE_KU,      "error.user.locked", null,
                "ئەکاونت کلۆزکراوە. تکایە دواتر هەوڵ بدەرەوە."));
        body.setDetails(Map.of(
                "maxAttempts",         SecurityConstants.MAX_FAILED_ATTEMPTS,
                "lockDurationMinutes", SecurityConstants.LOCK_DURATION_MINUTES,
                "hint", "Your account is temporarily locked for security reasons. " +
                        "It will automatically unlock after " + SecurityConstants.LOCK_DURATION_MINUTES +
                        " minute(s). Alternatively, use 'Forgot password' to regain access immediately."
        ));
        log.warn("AccountLocked path={} traceId={}", req.getRequestURI(), body.getTraceId());
        return ResponseEntity.status(HttpStatus.valueOf(423)).body(body);
    }
    /**
     * 404 NOT_FOUND — No user exists for the given username/email.
     *
     * details:
     *   hint → check spelling or register
     */
    @ExceptionHandler(UsernameNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleUsernameNotFound(
            UsernameNotFoundException ex, HttpServletRequest req, Locale locale) {
        ApiErrorResponse body = base(req, 404, ErrorCode.NOT_FOUND);
        body.setMessage  (resolve(locale,         "error.user.not_found", null, "User not found."));
        body.setMessageEn(resolve(Locale.ENGLISH, "error.user.not_found", null, "User not found."));
        body.setMessageKu(resolve(LOCALE_KU,      "error.user.not_found", null, "بەکارهێنەر نەدۆزرایەوە."));
        body.setDetails(Map.of(
                "hint", "Verify your username or email address. " +
                        "If you do not have an account yet, please register."
        ));
        log.warn("UsernameNotFound path={} traceId={}", req.getRequestURI(), body.getTraceId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleEntityNotFound(
            EntityNotFoundException ex, HttpServletRequest req, Locale locale) {
        String reason = ex.getMessage() != null ? ex.getMessage() : "Resource not found.";
        ApiErrorResponse body = base(req, 404, ErrorCode.NOT_FOUND);
        body.setMessage(reason);
        body.setMessageEn(reason);
        body.setMessageKu(reason);
        body.setDetails(Map.of("resource", reason));
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }
    /**
     * 404 NOT_FOUND — No handler is mapped to this URL at all (a typo, or a client
     * calling an endpoint this build does not have yet).
     *
     * Without this, the {@code Exception.class} fallback below catches Spring's
     * "no route" exception and answers 500, which reads as "the server is broken"
     * when the truth is "that path does not exist here".
     *
     * details:
     *   path   → the requested path
     *   method → HTTP method used
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiErrorResponse> handleNoHandler(
            Exception ex, HttpServletRequest req, Locale locale) {
        ApiErrorResponse body = base(req, 404, ErrorCode.NOT_FOUND);
        String fallbackEn = "No endpoint " + req.getMethod() + " " + req.getRequestURI() + ".";
        body.setMessage  (resolve(locale,         "error.http.no_handler", null, fallbackEn));
        body.setMessageEn(resolve(Locale.ENGLISH, "error.http.no_handler", null, fallbackEn));
        body.setMessageKu(resolve(LOCALE_KU,      "error.http.no_handler", null, "ئەم ڕێڕەوە بوونی نییە."));
        body.setDetails(Map.of(
                "path",   req.getRequestURI(),
                "method", req.getMethod(),
                "hint",   "Check the URL, and that the deployed build actually has this endpoint."
        ));
        log.warn("NoHandlerFound path={} method={} traceId={}",
                req.getRequestURI(), req.getMethod(), body.getTraceId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }
    /**
     * 403 FORBIDDEN — User is authenticated but does not have the required role or
     * permission for this resource.
     *
     * details:
     *   path   → the requested path
     *   method → HTTP method used
     *   hint   → contact admin if access should be granted
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest req, Locale locale) {
        ApiErrorResponse body = base(req, 403, ErrorCode.FORBIDDEN);
        body.setMessage  (resolve(locale,         "error.user.access_denied", null, "You do not have permission to access this resource."));
        body.setMessageEn(resolve(Locale.ENGLISH, "error.user.access_denied", null, "You do not have permission to access this resource."));
        body.setMessageKu(resolve(LOCALE_KU,      "error.user.access_denied", null,
                "ڕێگەپێنەدراو — ئەمەی خواستت تێپەڕاندنی ئاستی دەستوور دەخوازێت."));
        body.setDetails(Map.of(
                "path",   req.getRequestURI(),
                "method", req.getMethod(),
                "hint",   "Your current role does not allow this action. " +
                          "Contact an administrator if you believe you should have access."
        ));
        log.warn("AccessDenied path={} method={} traceId={}", req.getRequestURI(), req.getMethod(), body.getTraceId());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
    /**
     * 400 BAD_REQUEST — {@link IllegalArgumentException}.
     * Examples: invalid image type, file empty, pincode out of range.
     *
     * details:
     *   reason → the exception's message (already user-readable in the codebase)
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest req, Locale locale) {
        String reason = ex.getMessage() != null ? ex.getMessage() : "Invalid input.";
        ApiErrorResponse body = base(req, 400, ErrorCode.BAD_REQUEST);
        body.setMessage  (resolve(locale,         "error.bad_request", null, reason));
        body.setMessageEn(resolve(Locale.ENGLISH, "error.bad_request", null, reason));
        body.setMessageKu(resolve(LOCALE_KU,      "error.bad_request", null, reason));
        body.setDetails(Map.of("reason", reason));
        log.warn("IllegalArgument path={} traceId={} msg={}", req.getRequestURI(), body.getTraceId(), reason);
        return ResponseEntity.badRequest().body(body);
    }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalState(
            IllegalStateException ex, HttpServletRequest req, Locale locale) {
        String reason = ex.getMessage() != null ? ex.getMessage() : "Operation not permitted in the current state.";
        ApiErrorResponse body = base(req, 400, ErrorCode.BAD_REQUEST);
        body.setMessage  (resolve(locale,         "error.bad_request", null, reason));
        body.setMessageEn(resolve(Locale.ENGLISH, "error.bad_request", null, reason));
        body.setMessageKu(resolve(LOCALE_KU,      "error.bad_request", null, reason));
        body.setDetails(Map.of("reason", reason));
        log.warn("IllegalState path={} traceId={} msg={}", req.getRequestURI(), body.getTraceId(), reason);
        return ResponseEntity.badRequest().body(body);
    }
    // ═══════════════════════════════════════════════════════════════════════════
    // VALIDATION Exceptions
    // ═══════════════════════════════════════════════════════════════════════════
    /**
     * 400 VALIDATION_FAILED — @Valid / @RequestBody DTO validation failed.
     * Returns per-field error objects with trilingual messages.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest req, Locale locale) {
        ApiErrorResponse body = base(req, 400, ErrorCode.VALIDATION_ERROR);
        body.setMessage  (resolve(locale,         "error.validation", null, "One or more fields failed validation."));
        body.setMessageEn(resolve(Locale.ENGLISH, "error.validation", null, "One or more fields failed validation."));
        body.setMessageKu(resolve(LOCALE_KU,      "error.validation", null, "هەڵەی پشکنینەوە لە کێبڕکێی یان زیاتر."));
        List<ApiFieldError> fieldErrors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            String msgKey = fe.getDefaultMessage();
            String defEn  = fe.getDefaultMessage();
            fieldErrors.add(ApiFieldError.builder()
                    .field    (fe.getField())
                    .message  (resolve(locale,         msgKey, null, defEn))
                    .messageEn(resolve(Locale.ENGLISH, msgKey, null, defEn))
                    .messageKu(resolve(LOCALE_KU,      msgKey, null, defEn))
                    .build());
        }
        body.setFieldErrors(fieldErrors);
        log.warn("ValidationFailed path={} traceId={} fieldCount={}",
                req.getRequestURI(), body.getTraceId(), fieldErrors.size());
        return ResponseEntity.badRequest().body(body);
    }
    /**
     * 400 VALIDATION_FAILED — @Validated @RequestParam / path-variable constraint failed.
     * (e.g. the @Email constraint on /api/auth/reset-token?email=...)
     *
     * details:
     *   fieldErrors → each violated parameter with its message
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest req, Locale locale) {
        ApiErrorResponse body = base(req, 400, ErrorCode.VALIDATION_ERROR);
        body.setMessage  (resolve(locale,         "error.validation", null, "One or more request parameters are invalid."));
        body.setMessageEn(resolve(Locale.ENGLISH, "error.validation", null, "One or more request parameters are invalid."));
        body.setMessageKu(resolve(LOCALE_KU,      "error.validation", null, "هەڵەی پشکنینەوە لە پارامیتەرەکاندا."));
        List<ApiFieldError> fieldErrors = ex.getConstraintViolations().stream()
                .map(cv -> {
                    String path  = cv.getPropertyPath().toString();
                    // strip method prefix: "createResetToken.email" → "email"
                    String param = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
                    String msg   = cv.getMessage();
                    return ApiFieldError.builder()
                            .field    (param)
                            .message  (msg)
                            .messageEn(msg)
                            .messageKu(msg)
                            .build();
                })
                .collect(Collectors.toList());
        body.setFieldErrors(fieldErrors);
        log.warn("ConstraintViolation path={} traceId={} violations={}",
                req.getRequestURI(), body.getTraceId(), ex.getConstraintViolations().size());
        return ResponseEntity.badRequest().body(body);
    }
    // ═══════════════════════════════════════════════════════════════════════════
    // HTTP / REQUEST Exceptions
    // ═══════════════════════════════════════════════════════════════════════════
    /**
     * 405 METHOD_NOT_ALLOWED — Client used the wrong HTTP verb.
     *
     * details:
     *   usedMethod       → what the client sent
     *   supportedMethods → what the endpoint actually accepts
     *   hint             → check the docs
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest req, Locale locale) {
        String supported = ex.getSupportedMethods() != null
                ? String.join(", ", ex.getSupportedMethods()) : "N/A";
        ApiErrorResponse body = base(req, 405, ErrorCode.METHOD_NOT_ALLOWED);
        body.setMessage  (resolve(locale,         "error.http.method_not_allowed", null,
                "HTTP method '" + ex.getMethod() + "' is not supported for this endpoint. Use: " + supported));
        body.setMessageEn(resolve(Locale.ENGLISH, "error.http.method_not_allowed", null,
                "HTTP method '" + ex.getMethod() + "' is not supported. Allowed: " + supported));
        body.setMessageKu(resolve(LOCALE_KU,      "error.http.method_not_allowed", null,
                "ئەم جۆرە داواکاریی (" + ex.getMethod() + ") بۆ ئەم ئامرازەیە قبوڵ نەکراوە."));
        body.setDetails(Map.of(
                "usedMethod",       ex.getMethod(),
                "supportedMethods", supported,
                "hint",             "Please check the API documentation for the correct HTTP method."
        ));
        log.warn("MethodNotAllowed method={} path={} traceId={}", ex.getMethod(), req.getRequestURI(), body.getTraceId());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body);
    }
    /**
     * 400 BAD_REQUEST — Request body is missing, empty, or contains malformed JSON.
     * Common causes: missing Content-Type header, wrong data type for a field,
     * trailing comma in JSON, or completely empty body.
     *
     * details:
     *   hint → how to fix it
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableMessage(
            HttpMessageNotReadableException ex, HttpServletRequest req, Locale locale) {
        UnrecognizedPropertyException unknownProperty =
                findCause(ex, UnrecognizedPropertyException.class);
        if (unknownProperty != null) {
            return unknownPropertyResponse(unknownProperty, req, locale);
        }

        ApiErrorResponse body = base(req, 400, ErrorCode.BAD_REQUEST);
        body.setMessage  (resolve(locale,         "error.http.unreadable_body", null,
                "The request body is missing or contains invalid JSON."));
        body.setMessageEn(resolve(Locale.ENGLISH, "error.http.unreadable_body", null,
                "The request body is missing or contains invalid JSON."));
        body.setMessageKu(resolve(LOCALE_KU,      "error.http.unreadable_body", null,
                "جەستەی داواکاری بەتاڵە یان فۆرماتی JSON هەڵەیە."));
        body.setDetails(Map.of(
                "hint", "Make sure the Content-Type header is 'application/json' and the body is " +
                        "valid JSON. Common mistakes: missing quotes around strings, trailing commas, " +
                        "wrong value type for a field (e.g. string instead of number)."
        ));
        log.warn("UnreadableBody path={} traceId={}", req.getRequestURI(), body.getTraceId());
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 400 BAD_REQUEST — A JSON field is not accepted by the request DTO.
     *
     * This handler is needed for multipart controllers that deserialize their
     * JSON "data" part directly through ObjectMapper.
     */
    @ExceptionHandler(UnrecognizedPropertyException.class)
    public ResponseEntity<ApiErrorResponse> handleUnrecognizedProperty(
            UnrecognizedPropertyException ex, HttpServletRequest req, Locale locale) {
        return unknownPropertyResponse(ex, req, locale);
    }
    /**
     * 400 BAD_REQUEST — A required @RequestParam is absent from the request URL.
     * Example: POST /api/auth/reset-token called without ?email=...
     *
     * details:
     *   missingParameter → name of the missing query param
     *   expectedType     → expected Java type of the param
     *   hint             → how to add it
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest req, Locale locale) {
        ApiErrorResponse body = base(req, 400, ErrorCode.MISSING_PARAMETER);
        body.setMessage  (resolve(locale,         "error.http.missing_param", null,
                "Required parameter '" + ex.getParameterName() + "' is missing."));
        body.setMessageEn(resolve(Locale.ENGLISH, "error.http.missing_param", null,
                "Required parameter '" + ex.getParameterName() + "' is missing."));
        body.setMessageKu(resolve(LOCALE_KU,      "error.http.missing_param", null,
                "پارامیتەری پێویستی '" + ex.getParameterName() + "' نەگەیشتووە."));
        body.setDetails(Map.of(
                "missingParameter", ex.getParameterName(),
                "expectedType",     ex.getParameterType(),
                "hint",             "Append '?" + ex.getParameterName() + "=<value>' to your request URL."
        ));
        log.warn("MissingParam param={} path={} traceId={}", ex.getParameterName(), req.getRequestURI(), body.getTraceId());
        return ResponseEntity.badRequest().body(body);
    }
    /**
     * 413 PAYLOAD_TOO_LARGE — Uploaded file exceeds the server's configured max size.
     *
     * details:
     *   maxAllowedMB → maximum allowed size in megabytes
     *   hint         → compress/resize before re-uploading
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleMaxUploadSize(
            MaxUploadSizeExceededException ex, HttpServletRequest req, Locale locale) {
        ApiErrorResponse body = base(req, 413, ErrorCode.PAYLOAD_TOO_LARGE);
        body.setMessage  (resolve(locale,         "error.http.payload_too_large", null,
                "The uploaded file exceeds the maximum allowed size of " + MAX_UPLOAD_MB + " MB."));
        body.setMessageEn(resolve(Locale.ENGLISH, "error.http.payload_too_large", null,
                "The uploaded file exceeds the maximum allowed size of " + MAX_UPLOAD_MB + " MB."));
        body.setMessageKu(resolve(LOCALE_KU,      "error.http.payload_too_large", null,
                "قەبارەی فایلی بارکراو زیاتر لە " + MAX_UPLOAD_MB + " مێگابایت رێگەپێدراوەیە."));
        body.setDetails(Map.of(
                "maxAllowedMB", MAX_UPLOAD_MB,
                "hint",         "Please compress or resize your file and try again. " +
                                "Maximum file size is " + MAX_UPLOAD_MB + " MB. " +
                                "Accepted formats: JPEG, PNG, GIF, WebP."
        ));
        log.warn("PayloadTooLarge path={} traceId={}", req.getRequestURI(), body.getTraceId());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }
    /**
     * 400 BAD_REQUEST — Malformed multipart/form-data request or missing file part.
     *
     * details:
     *   hint → correct Content-Type and required parts
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiErrorResponse> handleMultipart(
            MultipartException ex, HttpServletRequest req, Locale locale) {
        ApiErrorResponse body = base(req, 400, ErrorCode.BAD_REQUEST);
        body.setMessage  (resolve(locale,         "error.http.multipart", null,
                "The multipart request is malformed or missing required file parts."));
        body.setMessageEn(resolve(Locale.ENGLISH, "error.http.multipart", null,
                "The multipart request is malformed or missing required file parts."));
        body.setMessageKu(resolve(LOCALE_KU,      "error.http.multipart", null,
                "داواکاریی multipart هەڵەیە یان بەشی فایل نەدۆزرایەوە."));
        body.setDetails(Map.of(
                "hint", "Set the Content-Type to 'multipart/form-data' and include all required file parts. " +
                        "For image upload use part name 'file'; for registration-with-image use 'data' + 'image'."
        ));
        log.warn("MultipartException path={} traceId={} msg={}", req.getRequestURI(), body.getTraceId(), ex.getMessage());
        return ResponseEntity.badRequest().body(body);
    }
    // ═══════════════════════════════════════════════════════════════════════════
    // DATABASE Exceptions
    // ═══════════════════════════════════════════════════════════════════════════
    /**
     * 409 CONFLICT — DB-level unique-constraint violation.
     * This is a safety net: the service layer should throw UserAlreadyExistsException
     * first, but if a race condition slips through, this handler catches it.
     *
     * details:
     *   hint → duplicate data; choose unique values
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, HttpServletRequest req, Locale locale) {
        ApiErrorResponse body = base(req, 409, ErrorCode.CONFLICT);
        body.setMessage  (resolve(locale,         "error.db.conflict", null, "A record with this data already exists."));
        body.setMessageEn(resolve(Locale.ENGLISH, "error.db.conflict", null, "A record with this data already exists. Please use unique values."));
        body.setMessageKu(resolve(LOCALE_KU,      "error.db.conflict", null, "تۆمارێک بەم داتایە پێشتر هەیە."));
        body.setDetails(Map.of(
                "hint", "The username or email you provided is already in use. Please choose a different one."
        ));
        log.error("DataIntegrityViolation path={} traceId={} cause={}",
                req.getRequestURI(), body.getTraceId(), ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
    // ═══════════════════════════════════════════════════════════════════════════
    // FALLBACK
    // ═══════════════════════════════════════════════════════════════════════════
    /**
     * 500 INTERNAL_ERROR — Catch-all for any uncovered exception.
     * Logs the full stack trace server-side. Never exposes it to the client.
     *
     * details:
     *   traceId → unique ID the user can quote when reporting the issue
     *   hint    → contact support with traceId
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnknown(
            Exception ex, HttpServletRequest req, Locale locale) {
        ApiErrorResponse body = base(req, 500, ErrorCode.INTERNAL_ERROR);
        body.setMessage  (resolve(locale,         "error.internal", null, "An unexpected error occurred. Please try again later."));
        body.setMessageEn(resolve(Locale.ENGLISH, "error.internal", null, "An unexpected error occurred. Please try again later."));
        body.setMessageKu(resolve(LOCALE_KU,      "error.internal", null, "هەڵەیەکی چاونەچاواو ڕوویدا. تکایە دواتر هەوڵبدەرەوە."));
        body.setDetails(Map.of(
                "traceId", body.getTraceId() != null ? body.getTraceId() : "N/A",
                "hint",    "If this error persists, please contact support and provide the traceId above."
        ));
        log.error("UnhandledException path={} method={} traceId={}",
                req.getRequestURI(), req.getMethod(), body.getTraceId(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════
    private ApiErrorResponse base(HttpServletRequest req, int status, ErrorCode code) {
        return ApiErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status)
                .code(code)
                .path(req.getRequestURI())
                .method(req.getMethod())
                .traceId(org.slf4j.MDC.get(TraceIdFilter.TRACE_ID))
                .build();
    }

    private ResponseEntity<ApiErrorResponse> unknownPropertyResponse(
            UnrecognizedPropertyException ex, HttpServletRequest req, Locale locale) {
        String propertyName = ex.getPropertyName();
        String fallback = "Unknown field in request body: " + propertyName;

        ApiErrorResponse body = base(req, 400, ErrorCode.BAD_REQUEST);
        body.setMessage(resolve(locale, "error.http.unknown_field",
                new Object[]{propertyName}, fallback));
        body.setMessageEn(resolve(Locale.ENGLISH, "error.http.unknown_field",
                new Object[]{propertyName}, fallback));
        body.setMessageKu(resolve(LOCALE_KU, "error.http.unknown_field",
                new Object[]{propertyName}, fallback));
        body.setDetails(Map.of(
                "unknownField", propertyName,
                "hint", "Remove this field or check its spelling against the API request schema."
        ));

        log.warn("UnknownJsonField field={} path={} traceId={}",
                propertyName, req.getRequestURI(), body.getTraceId());
        return ResponseEntity.badRequest().body(body);
    }

    private <T extends Throwable> T findCause(Throwable throwable, Class<T> causeType) {
        Throwable current = throwable;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return causeType.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private String resolve(Locale locale, String key, Object[] args, String defaultMessage) {
        if (key == null || key.isBlank()) return defaultMessage;
        try {
            return messageSource.getMessage(key, args, defaultMessage, locale);
        } catch (Exception ignore) {
            return defaultMessage;
        }
    }
    private String fallbackByCode(ErrorCode code, String lang) {
        boolean ku = "ku".equals(lang);
        return switch (code) {
            case NOT_FOUND          -> ku ? "سەرچاوە نەدۆزرایەوە"                                            : "Resource not found";
            case BAD_REQUEST        -> ku ? "داواکاری هەڵەیە"                                                 : "Bad request";
            case CONFLICT           -> ku ? "کێشەی تێکچوون هەیە"                                             : "Conflict";
            case UNAUTHORIZED       -> ku ? "ڕێگەپێنەدراو"                                                   : "Unauthorized";
            case FORBIDDEN          -> ku ? "قەدەغەکراوە"                                                     : "Forbidden";
            case ACCOUNT_LOCKED     -> ku ? "ئەکاونت کلۆزکراوە. تکایە دواتر هەوڵ بدەرەوە."                 : "Account locked. Please try again later.";
            case VALIDATION_ERROR   -> ku ? "هەڵەی پشکنینەوە"                                                : "Validation error";
            case MISSING_PARAMETER  -> ku ? "پارامیتەری پێویست کەمە"                                         : "Missing required parameter";
            case METHOD_NOT_ALLOWED -> ku ? "ئەم جۆرە داواکاریی بۆ ئەم ئامرازەیە قبوڵ نەکراوە"             : "HTTP method not allowed";
            case PAYLOAD_TOO_LARGE  -> ku ? "قەبارەی فایل زۆر زیادە"                                        : "File size too large";
            // ── Project-specific ───────────────────────────────────
            case PROJECT_NOT_FOUND  -> ku ? "پرۆژە نەدۆزرایەوە"                                              : "Project not found";
            case PROJECT_CONFLICT   -> ku ? "کێشەی تێکچوون لە پرۆژەدا"                                      : "Project data conflict";
            case PROJECT_VALIDATION -> ku ? "هەڵەی پشکنینەوە لە داتای پرۆژەدا"                              : "Project validation error";
            case PROJECT_MEDIA_INVALID -> ku ? "داتای میدیای پرۆژە هەڵەیە"                                   : "Invalid project media data";
            // ── News-specific ───────────────────────────────────────
            case NEWS_NOT_FOUND     -> ku ? "هەواڵ نەدۆزرایەوە"                                               : "News not found";
            case NEWS_CONFLICT      -> ku ? "کێشەی تێکچوون لە هەواڵدا"                                         : "News data conflict";
            case NEWS_VALIDATION    -> ku ? "هەڵەی پشکنینەوە لە داتای هەواڵدا"                                  : "News validation error";
            case NEWS_MEDIA_INVALID -> ku ? "داتای میدیای هەواڵ هەڵەیە"                                         : "Invalid news media data";
            // ── Image-specific ──────────────────────────────────────
            case IMAGE_NOT_FOUND     -> ku ? "کۆمەڵەی وێنە نەدۆزرایەوە"                                        : "Image collection not found";
            case IMAGE_CONFLICT      -> ku ? "کێشەی تێکچوون لە کۆمەڵەی وێنەدا"                                   : "Image data conflict";
            case IMAGE_VALIDATION    -> ku ? "هەڵەی پشکنینەوە لە داتای وێنەدا"                                     : "Image validation error";
            case IMAGE_MEDIA_INVALID -> ku ? "داتای میدیای وێنە هەڵەیە"                                         : "Invalid image media data";
            // ── Sound-specific ──────────────────────────────────────
            case SOUND_NOT_FOUND     -> ku ? "سەدا نەدۆزرایەوە"                                                 : "SoundTrack not found";
            case SOUND_CONFLICT      -> ku ? "کێشەی تێکچوون لە سەدا"                                            : "SoundTrack data conflict";
            case SOUND_VALIDATION    -> ku ? "هەڵەی پشکنینەوە لە داتای سەدا"                                      : "Sound validation error";
            case SOUND_MEDIA_INVALID -> ku ? "داتای میدیای سەدا هەڵەیە"                                          : "Invalid sound media data";
            default                 -> ku ? "هەڵەی ناوخۆیی"                                                  : "Internal error";
        };
    }
}
