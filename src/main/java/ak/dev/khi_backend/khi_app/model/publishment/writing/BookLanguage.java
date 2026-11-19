package ak.dev.khi_backend.khi_app.model.publishment.writing;

import jakarta.persistence.*;
import lombok.*;

/**
 * زمانی کتێب — the Kurdish variety/dialect a book is written in. Six rows are
 * seeded (Sorani + Kurmanji spellings of upper/middle/lower Kurdish); admins
 * extend the list. The row's names also feed the legacy
 * {@code bookLanguageCkb}/{@code bookLanguageKmr} text fields so the public
 * site keeps working unchanged.
 */
@Entity
@Table(name = "book_languages", uniqueConstraints = {
        @UniqueConstraint(name = "uk_book_language_slug", columnNames = "slug")
})
@NoArgsConstructor
public class BookLanguage extends ManagedOption {
}
