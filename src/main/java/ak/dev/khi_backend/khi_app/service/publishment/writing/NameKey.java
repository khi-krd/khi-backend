package ak.dev.khi_backend.khi_app.service.publishment.writing;

import java.util.Locale;

/** Normalised comparison key for vocabulary names: trimmed, collapsed spaces, lower-case, Arabic ي/ك folded to Kurdish ی/ک. */
public final class NameKey {
    private NameKey() {}

    public static String of(String name) {
        if (name == null || name.isBlank()) return null;
        return name.trim().replaceAll("\\s+", " ")
                .replace('\u064A', '\u06CC').replace('\u0643', '\u06A9')
                .replace("\u200C", "").toLowerCase(Locale.ROOT);
    }
}
