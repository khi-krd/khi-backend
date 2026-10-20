package ak.dev.khi_backend.khi_app.model.contact;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Contact — Contact-page entity.
 *
 * ─── Bilingual Slugs ──────────────────────────────────────────────────────────
 *  slugCkb  → Sorani   route identifier
 *  slugKmr  → Kurmanji route identifier
 *
 * ─── Bilingual Content ────────────────────────────────────────────────────────
 *  ckbContent / kmrContent — title, subtitle, address, workingHours, and a
 *  Tiptap HTML {@code description} per language.
 *
 *  All visual media (image, video, voice, document, or any other file)
 *  lives INSIDE the bilingual {@code description} HTML as inline
 *  {@code <img>}, {@code <video>}, {@code <audio>}, or {@code <a href>}
 *  tags whose URLs already point at S3.  The
 *  {@link ak.dev.khi_backend.khi_app.service.media.TiptapHtmlProcessor}
 *  intercepts every save and hoists any inline base64 payload up to S3
 *  before persisting.
 *
 *  Contact no longer carries a hero image, hero media type, hero thumbnail,
 *  or media gallery field — all such concerns now live as inline elements
 *  of the Tiptap description.
 *
 * ─── Contact Details (language-agnostic) ─────────────────────────────────────
 *  phone, secondaryPhone, email, mapEmbedUrl, latitude, longitude
 */
@Entity
@Table(
        name = "contact_pages",
        indexes = {
                @Index(name = "idx_contact_slug_ckb", columnList = "slug_ckb"),
                @Index(name = "idx_contact_slug_kmr", columnList = "slug_kmr"),
                @Index(name = "idx_contact_active",   columnList = "active")
        }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Contact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ─── Bilingual Slugs ──────────────────────────────────────────────────────

    @NotBlank
    @Column(name = "slug_ckb", unique = true, nullable = false, length = 200)
    private String slugCkb;

    @Column(name = "slug_kmr", unique = true, nullable = true, length = 200)
    private String slugKmr;

    @Builder.Default
    private boolean active = true;

    @Column(name = "display_order")
    @Builder.Default
    private Integer displayOrder = 0;

    // ─── CKB (Sorani) Content ─────────────────────────────────────────────────

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "title",        column = @Column(name = "title_ckb",         length = 300)),
            @AttributeOverride(name = "subtitle",     column = @Column(name = "subtitle_ckb",      length = 500)),
            @AttributeOverride(name = "address",      column = @Column(name = "address_ckb",       length = 500)),
            @AttributeOverride(name = "workingHours", column = @Column(name = "working_hours_ckb", length = 300)),
            @AttributeOverride(name = "description",  column = @Column(name = "description_ckb",   columnDefinition = "TEXT"))
    })
    private ContactContent ckbContent;

    // ─── KMR (Kurmanji) Content ───────────────────────────────────────────────

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "title",        column = @Column(name = "title_kmr",         length = 300)),
            @AttributeOverride(name = "subtitle",     column = @Column(name = "subtitle_kmr",      length = 500)),
            @AttributeOverride(name = "address",      column = @Column(name = "address_kmr",       length = 500)),
            @AttributeOverride(name = "workingHours", column = @Column(name = "working_hours_kmr", length = 300)),
            @AttributeOverride(name = "description",  column = @Column(name = "description_kmr",   columnDefinition = "TEXT"))
    })
    private ContactContent kmrContent;

    // ─── Contact Details (language-agnostic) ──────────────────────────────────

    /** Primary phone number — e.g. "+964 770 123 4567" */
    @Column(name = "phone", length = 60)
    private String phone;

    /** Secondary / additional phone number */
    @Column(name = "secondary_phone", length = 60)
    private String secondaryPhone;

    /** Primary contact email */
    @Column(name = "email", length = 200)
    private String email;

    /**
     * Google Maps embed URL or any iframe-compatible map URL.
     * Used on the public contact page for the embedded map widget.
     */
    @Column(name = "map_embed_url", columnDefinition = "TEXT")
    private String mapEmbedUrl;

    /** Latitude for custom map marker or Open Maps link */
    @Column(name = "latitude")
    private Double latitude;

    /** Longitude for custom map marker or Open Maps link */
    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "hero_image_url", columnDefinition = "TEXT")
    private String heroImageUrl;

    /** HQ or REGIONAL; kept as text so additional office types do not require a migration.
     *  Primary (first) office type — the full selection lives in officeTypes. */
    @Column(name = "office_type", length = 40)
    private String officeType;

    /** Every selected office type — the full multi-select set. */
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "contact_office_types",
            joinColumns = @JoinColumn(name = "contact_id")
    )
    @Column(name = "office_type", nullable = false, length = 40)
    @BatchSize(size = 50)
    private Set<String> officeTypes = new LinkedHashSet<>();

    @Column(name = "badge_ckb", length = 200)
    private String badgeCkb;

    @Column(name = "badge_kmr", length = 200)
    private String badgeKmr;

    // ─── Timestamps ───────────────────────────────────────────────────────────

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
