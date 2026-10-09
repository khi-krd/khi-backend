package ak.dev.khi_backend.khi_app.model.site;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * An editable UI text block ("static text"). Rows are keyed by a dotted
 * message key plus a locale, e.g. ("Nav.menuNews", "ckb") -> "هەواڵ".
 * The public website merges these over its bundled JSON messages; the
 * dashboard edits them. Rows are seeded once from the website's bundled
 * message files and are never overwritten afterwards.
 */
@Entity
@Table(
        name = "static_texts",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_static_texts_key_locale",
                columnNames = {"text_key", "locale"}
        ),
        indexes = {
                @Index(name = "idx_static_texts_locale", columnList = "locale"),
                @Index(name = "idx_static_texts_section", columnList = "section")
        }
)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StaticText {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Dotted message key, e.g. "Nav.menuNews" or "Home.Hero.title". */
    @Column(name = "text_key", nullable = false, length = 300)
    private String key;

    /** BCP-47-ish locale tag used by the frontends: "ckb", "ku". */
    @Column(name = "locale", nullable = false, length = 10)
    private String locale;

    @Column(name = "value", nullable = false, columnDefinition = "TEXT")
    private String value;

    /** Top-level key segment ("Nav", "Home"…) kept for grouping/filtering. */
    @Column(name = "section", length = 120)
    private String section;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;

    @PrePersist
    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }
}
