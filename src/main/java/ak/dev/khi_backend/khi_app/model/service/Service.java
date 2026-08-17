package ak.dev.khi_backend.khi_app.model.service;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

/**
 * Service — A single institute service (Training, Event, Program, etc.).
 *
 * ─── Bilingual Content ────────────────────────────────────────────────────────
 *  Bilingual text lives in a separate {@link ServiceContent} table joined by
 *  (service_id, language_code).  This makes adding more languages later trivial.
 *
 *    service_contents WHERE language_code = 'CKB'  → Sorani   version
 *    service_contents WHERE language_code = 'KMR'  → Kurmanji version
 *
 *  Each {@link ServiceContent} row carries a Tiptap HTML {@code description}
 *  which is the ONLY place media (image, video, voice, document, or any
 *  other file) is stored.  Inline {@code <img>}, {@code <video>},
 *  {@code <audio>}, and {@code <a href>} tags reference S3 URLs;
 *  {@link ak.dev.khi_backend.khi_app.service.media.TiptapHtmlProcessor}
 *  hoists any inline base64 payloads to S3 at save time.
 *
 *  Service no longer carries any cover, hero, thumbnail, or
 *  per-file-metadata media model.  The {@code service_media_collections}
 *  and {@code service_media_files} tables — and their POJO classes — have
 *  been removed.
 *
 * ─── Publish Lifecycle ────────────────────────────────────────────────────────
 *  {@link #publishedAt}  — explicit publish timestamp set by the admin.
 *  {@link #active}       — soft-disable without deleting.
 */
@Entity
@Table(
        name = "services",
        indexes = {
                @Index(name = "idx_service_type",   columnList = "service_type"),
                @Index(name = "idx_service_active", columnList = "active"),
                @Index(name = "idx_service_published_at", columnList = "published_at"),
                @Index(name = "idx_service_sort_order", columnList = "sort_order")
        }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Service {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ─── Core Fields ──────────────────────────────────────────────────────────

    /**
     * Dynamic service type label — free text so admins can define new types
     * without a code change.
     */
    @NotBlank
    @Column(name = "service_type", nullable = false, length = 100)
    private String serviceType;

    /**
     * Physical or virtual location of the service.
     * Null when location is not applicable.
     */
    @Column(name = "location", length = 200)
    private String location;

    /** Soft-delete / visibility toggle.  Defaults to true (visible). */
    @Builder.Default
    @Column(name = "active")
    private boolean active = true;

    /**
     * Explicit publish timestamp set by the admin when going live.
     * Null means the service was never formally published.
     */
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    /**
     * Explicit nav / scroll order for the public Services page — lower first.
     * Null sorts last (falls back to publishedAt DESC, then createdAt DESC).
     */
    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "layout_type", length = 80)
    private String layoutType;
    @Column(name = "hero_video_url", columnDefinition = "TEXT")
    private String heroVideoUrl;
    @Column(name = "hero_poster_url", columnDefinition = "TEXT")
    private String heroPosterUrl;
    @Column(name = "nav_anchor_id", length = 160)
    private String navAnchorId;

    // ─── Featured (homepage carousel) ─────────────────────────────────────────
    //
    //  Same curated model as the publication modules. The slide title comes from
    //  {@link ServiceContent#getTitle()} and the slide copy from
    //  {@link ServiceContent#getFeatureDescription()} — the Tiptap description
    //  is HTML and is only used as a stripped-down fallback.

    @Builder.Default
    @Column(name = "featured")
    private boolean featured = false;

    @Column(name = "featured_order")
    private Integer featuredOrder;

    /** Wide picture for the homepage hero; falls back to the first gallery image. */
    @Column(name = "feature_image_url", columnDefinition = "TEXT")
    private String featureImageUrl;

    /**
     * RECOMMENDED gallery — ordered slots, each independently IMAGE or VIDEO.
     * List order is display order; any slot may be a video.
     */
    @ElementCollection
    @CollectionTable(name = "service_gallery_media", joinColumns = @JoinColumn(name = "service_id"))
    @OrderColumn(name = "display_order")
    @BatchSize(size = 50)
    @Builder.Default
    private List<ServiceMedia> galleryMedia = new ArrayList<>();

    /** Legacy gallery fallback — used only when {@link #galleryMedia} is empty. */
    @ElementCollection
    @CollectionTable(name = "service_feature_images", joinColumns = @JoinColumn(name = "service_id"))
    @Column(name = "image_url", columnDefinition = "TEXT")
    @OrderColumn(name = "display_order")
    @Builder.Default
    private List<String> featureImageUrls = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "service_thumbnail_images", joinColumns = @JoinColumn(name = "service_id"))
    @Column(name = "image_url", columnDefinition = "TEXT")
    @OrderColumn(name = "display_order")
    @Builder.Default
    private List<String> thumbnailUrls = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "service_partners", joinColumns = @JoinColumn(name = "service_id"))
    @Column(name = "partner_id")
    @OrderColumn(name = "display_order")
    @Builder.Default
    private List<Long> partnerIds = new ArrayList<>();

    // ─── Bilingual Content ────────────────────────────────────────────────────

    /**
     * CKB + KMR language rows.
     * Unique constraint on (service_id, language_code) prevents duplicates.
     */
    @OneToMany(mappedBy = "service", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @Builder.Default
    private Set<ServiceContent> contents = new HashSet<>();

    // ─── Timestamps ───────────────────────────────────────────────────────────

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ─── Helper Methods ───────────────────────────────────────────────────────

    public void addContent(ServiceContent content) {
        contents.add(content);
        content.setService(this);
    }

    public void removeContent(ServiceContent content) {
        contents.remove(content);
        content.setService(null);
    }
}
