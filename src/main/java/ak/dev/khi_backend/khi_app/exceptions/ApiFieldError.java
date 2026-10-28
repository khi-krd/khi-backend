package ak.dev.khi_backend.khi_app.exceptions;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiFieldError {
    private String field;

    /** Bean-validation constraint name (e.g. NotBlank, Size) when known. */
    private String code;

    // localized (based on Accept-Language)
    private String message;

    // always included for UI
    private String messageEn;
    private String messageKu;
}
