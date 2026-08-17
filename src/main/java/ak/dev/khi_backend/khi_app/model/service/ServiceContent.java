package ak.dev.khi_backend.khi_app.model.service;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.*;

/**
 * ServiceContent — One bilingual row per language for a {@link Service}.
 *
 * ─── Language Code Values ─────────────────────────────────────────────────────
 *  "CKB"  → Sorani   Kurdish
 *  "KMR"  → Kurmanji Kurdish
 *
 *  Future languages (e.g. "EN", "AR") can be added without any schema change —
 *  simply insert a new row with the new language_code.
 *
 * ─── Unique Constraint ────────────────────────────────────────────────────────
 *  UNIQUE(service_id, language_code) — one row per language per service.
 *
 * ─── Design Note ──────────────────────────────────────────────────────────────
 *  This differs from the About module which uses @Embeddable / @AttributeOverrides
 *  (two fixed language columns per table row).  The separate-row approach used
 *  here is more flexible: adding a third language requires no migration.
 */
@Entity
@Table(
        name = "service_contents",
        uniqueConstraints = {
                @UniqueConstraint(
                        name  = "uq_service_content_lang",
                        columnNames = {"service_id", "language_code"}
                )
        },
        indexes = {
                @Index(name = "idx_service_content_service_id", columnList = "service_id"),
                @Index(name = "idx_service_content_lang",       columnList = "language_code")
        }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServiceContent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ─── Language ─────────────────────────────────────────────────────────────

    /**
     * ISO-like language code for this row.
     * Accepted values: "CKB" (Sorani) | "KMR" (Kurmanji).
     * Validated at the service layer; stored as-is in the DB.
     */
    @NotBlank
    @Pattern(regexp = "^[A-Z]{2,5}$",
            message = "language_code must be 2–5 uppercase letters, e.g. CKB or KMR")
    @Column(name = "language_code", nullable = false, length = 10)
    private String languageCode;

    // ─── Content Fields ───────────────────────────────────────────────────────

    /** Localised service title. */
    @Column(name = "title", nullable = false, length = 300)
    private String title;

    /** Localised service description — rich text / long form. */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Short PLAIN-TEXT summary used by the homepage featured carousel.
     *
     * {@link #description} is Tiptap HTML and cannot be rendered inside a slide,
     * so the admin writes one short line per language here. When left blank the
     * featured mapper falls back to a tag-stripped excerpt of the description.
     */
    @Column(name = "feature_description", length = 1000)
    private String featureDescription;

    // ─── Parent ───────────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Service service;
}