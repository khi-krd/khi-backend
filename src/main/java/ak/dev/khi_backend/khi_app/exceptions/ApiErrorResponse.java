package ak.dev.khi_backend.khi_app.exceptions;

import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiErrorResponse {
    private Instant timestamp;
    private int status;
    private String path;
    private String method;
    private String traceId;

    /** Same value as {@link #traceId}, exposed under the X-Request-Id name. */
    private String requestId;

    private ErrorCode code;

    // localized message (Accept-Language)
    private String message;

    // bilingual always present
    private String messageEn;
    private String messageKu;

    private List<ApiFieldError> fieldErrors;

    // extra safe info (no stack traces)
    private Map<String, Object> details;
}
