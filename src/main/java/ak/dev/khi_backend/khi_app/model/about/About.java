package ak.dev.khi_backend.khi_app.model.about;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * About — About-page entity.
 *
 * ─── Bilingual Slugs ──────────────────────────────────────────────────────────
 *  slugCkb  → Sorani   route identifier
 *  slugKmr  → Kurmanji route identifier (nullable — page may be CKB-only)
 *
 * ─── Bilingual Content ────────────────────────────────────────────────────────
 *  ckbContent / kmrContent — title, subtitle, metaDescription, and a Tiptap
 *  HTML {@code body} per language.
 *
 *  All visual media — images, videos, voice / audio, and any other
 *  downloadable file — lives INSIDE the {@code body} HTML as inline
 *  {@code <img>}, {@code <video>}, {@code <audio>}, or {@code <a href>}
 *  tags whose URLs already point at S3.  The
 *  {@link ak.dev.khi_backend.khi_app.service.media.TiptapHtmlProcessor}
 *  intercepts every save and hoists any inline base64 payload up to S3
 *  before persisting, so the column never holds raw binary data.
 *
 *  About no longer has a hero image, hero media type, hero thumbnail, or
 *  media gallery field.  Anything previously rendered through those fields
 *  is now an inline element of the Tiptap body.
 *
 * ─── Stats ────────────────────────────────────────────────────────────────────
 *  STATS (array of {labelCkb, labelKmr, value}) survives as a dedicated
 *  JSONB column — it cannot be embedded cleanly in HTML and the frontend
 *  renders it from structured JSON.
 */
@Entity
@Table(
        name = "about_pages",
        indexes = {
                @Index(name = "idx_about_slug_ckb", columnList = "slug_ckb"),
                @Index(name = "idx_about_slug_kmr", columnList = "slug_kmr"),
                @Index(name = "idx_about_active",   columnList = "active")
        }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class About {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "title",           column = @Column(name = "title_ckb",            length = 300)),
            @AttributeOverride(name = "subtitle",        column = @Column(name = "subtitle_ckb",         length = 500)),
            @AttributeOverride(name = "metaDescription", column = @Column(name = "meta_description_ckb", length = 2500)),
            @AttributeOverride(name = "body",            column = @Column(name = "body_ckb",             columnDefinition = "TEXT"))
    })
    private AboutContent ckbContent;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "title",           column = @Column(name = "title_kmr",            length = 300)),
            @AttributeOverride(name = "subtitle",        column = @Column(name = "subtitle_kmr",         length = 500)),
            @AttributeOverride(name = "metaDescription", column = @Column(name = "meta_description_kmr", length = 2500)),
            @AttributeOverride(name = "body",            column = @Column(name = "body_kmr",             columnDefinition = "TEXT"))
    })
    private AboutContent kmrContent;

    /**
     * Structured stats — array of {labelCkb, labelKmr, value}.
     * Stored as JSONB so the order and shape are preserved.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stats", columnDefinition = "jsonb")
    @Builder.Default
    private List<StatItem> stats = new ArrayList<>();

    @Column(name = "founder_name_ckb", length = 300)
    private String founderNameCkb;
    @Column(name = "founder_name_kmr", length = 300)
    private String founderNameKmr;
    @Column(name = "founder_bio_ckb", columnDefinition = "TEXT")
    private String founderBioCkb;
    @Column(name = "founder_bio_kmr", columnDefinition = "TEXT")
    private String founderBioKmr;
    @Column(name = "founder_image_url", columnDefinition = "TEXT")
    private String founderImageUrl;
    @Column(name = "hero_video_url", columnDefinition = "TEXT")
    private String heroVideoUrl;
    @Column(name = "hero_poster_url", columnDefinition = "TEXT")
    private String heroPosterUrl;

    // ─── Featured (homepage carousel) ─────────────────────────────────────────
    //
    //  Same curated model as News / Project / Writing / Video / SoundTrack /
    //  ImageCollection: an admin flags the page and optionally orders it, and
    //  SiteContentService.getFeatured() folds it into the global slide list.
    //
    //  About carries no cover image — every picture lives inline in the Tiptap
    //  body — so featureImageUrl is NOT an override here, it is the only image
    //  the slide can use.  setAboutFeatured() rejects turning featured on while
    //  it is blank, otherwise the slide would silently never render.

    // nullable = false + a DB default is deliberate: ddl-auto adds a plain nullable column,
    // every pre-existing row would then hold NULL, and Hibernate cannot map NULL into a
    // primitive boolean — every read of this table would blow up. With the default,
    // "alter table … add column featured boolean default false not null" backfills instead.
    @Builder.Default
    @Column(name = "featured", nullable = false)
    @ColumnDefault("false")
    private boolean featured = false;

    @Column(name = "featured_order")
    private Integer featuredOrder;

    /** Wide picture for the homepage hero. Required while {@link #featured} is true. */
    @Column(name = "feature_image_url", columnDefinition = "TEXT")
    private String featureImageUrl;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
