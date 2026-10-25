package ak.dev.khi_backend.khi_app.enums.publishment;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The role a person plays in a book — writer, collector, translator, etc.
 * Localized labels live on the website/dashboard; the API only carries codes.
 */
public enum WritingPersonRole {
    WRITER,
    COLLECTOR,
    TRANSLATOR,
    EDITOR,
    NARRATOR,
    PHOTOGRAPHER,
    OWNER,
    OTHER;

    @JsonCreator
    public static WritingPersonRole from(String value) {
        if (value == null) return null;
        return WritingPersonRole.valueOf(value.trim().toUpperCase());
    }

    @JsonValue
    public String toJson() {
        return name();
    }
}
