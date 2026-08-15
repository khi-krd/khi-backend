package ak.dev.khi_backend.khi_app.model.publishment.image;

import ak.dev.khi_backend.khi_app.enums.Language;
import ak.dev.khi_backend.khi_app.enums.publishment.ImageCollectionType;
import ak.dev.khi_backend.khi_app.model.publishment.topic.PublishmentTopic;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ImageCollection — Image / photo publishment entity.
 *
 * ─── Collection Types ──────────────────────────────────────────────────────
 *  SINGLE      → exactly 1 image
 *  GALLERY     → multiple images (album)
 *  PHOTO_STORY → multiple images (story / process)
 *
 * ─── @BatchSize strategy ───────────────────────────────────────────────────
 *  All collections changed from EAGER → LAZY + @BatchSize(size = 50).
 *
 *  Without @BatchSize (old EAGER):
 *    Hibernate fires 1 massive LEFT JOIN across all collections
 *    = Cartesian product explosion for 20 items × tags × keywords × album
 *
 *  With @BatchSize (new LAZY):
 *    Q1: SELECT ic   FROM image_collections          WHERE id IN (...)
 *    Q2: SELECT ...  FROM image_collection_languages  WHERE image_collection_id IN (...)
 *    Q3: SELECT ...  FROM image_tags_ckb              WHERE image_collection_id IN (...)
 *    Q4: SELECT ...  FROM image_tags_kmr              WHERE image_collection_id IN (...)
 *    Q5: SELECT ...  FROM image_keywords_ckb          WHERE image_collection_id IN (...)
 *    Q6: SELECT ...  FROM image_keywords_kmr          WHERE image_collection_id IN (...)
 *    Q7: SELECT ...  FROM image_album_items           WHERE image_collection_id IN (...)
 *    Q8: SELECT ...  FROM publishment_topics          WHERE id IN (...)  ← @BatchSize on class
 *
 *    8 fast IN-queries for any page size vs 1 Cartesian monster.
 *
 * ─── DB Migration (run once when upgrading from old single-cover schema) ───
 *
 *  ALTER TABLE image_collections RENAME COLUMN cover_url TO ckb_cover_url;
 *  ALTER TABLE image_collections ALTER COLUMN ckb_cover_url DROP NOT NULL;
 *  ALTER TABLE image_collections ADD COLUMN IF NOT EXISTS kmr_cover_url   TEXT;
 *  ALTER TABLE image_collections ADD COLUMN IF NOT EXISTS hover_cover_url TEXT;
 *  ALTER TABLE image_collections ADD COLUMN IF NOT EXISTS topic_id BIGINT;
 *  ALTER TABLE image_collections
 *      ADD CONSTRAINT fk_img_coll_topic
 *      FOREIGN KEY (topic_id) REFERENCES publishment_topics(id) ON DELETE SET NULL;
 */
@Entity
@Table(
        name = "image_collections",
        indexes = {
                @Index(name = "idx_img_collection_type",  columnList = "collection_type"),
                @Index(name = "idx_img_topic",            columnList = "topic_id"),
                @Index(name = "idx_img_title_ckb",        columnList = "title_ckb"),
                @Index(name = "idx_img_title_kmr",        columnList = "title_kmr"),
                @Index(name = "idx_img_publishment_date", columnList = "publishment_date"),
                @Index(name = "idx_img_created_at",       columnList = "created_at"),
                @Index(name = "idx_img_updated_at",       columnList = "updated_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImageCollection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "slug_ckb", unique = true, length = 240)
    private String slugCkb;

    @Column(name = "slug_kmr", unique = true, length = 240)
    private String slugKmr;

    // ─── Collection Type ──────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "collection_type", nullable = false, length = 20)
    private ImageCollectionType collectionType;

    // ─── Cover Images (3 slots) ───────────────────────────────────────────────

    /** Sorani (CKB) cover — S3 uploaded or external URL. */
    @Column(name = "ckb_cover_url", columnDefinition = "TEXT")
    private String ckbCoverUrl;

    /** Kurmanji (KMR) cover — S3 uploaded or external URL. */
    @Column(name = "kmr_cover_url", columnDefinition = "TEXT")
    private String kmrCoverUrl;

    /** Hover overlay image — shown on mouse-over in list / card views. */
    @Column(name = "hover_cover_url", columnDefinition = "TEXT")
    private String hoverCoverUrl;

    // ─── Topic ────────────────────────────────────────────────────────────────
    //
    // LAZY + @BatchSize on the PublishmentTopic CLASS (not here).
    // When a page of N collections is loaded, Hibernate fires
    // 1 IN-query to load all their topics instead of N queries.

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "topic_id")
    private PublishmentTopic topic;

    // ─── CKB (Sorani) Embedded Content ───────────────────────────────────────

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "title",
                    column = @Column(name = "title_ckb",        length = 300)),
            @AttributeOverride(name = "description",
                    column = @Column(name = "description_ckb",  columnDefinition = "TEXT")),
            @AttributeOverride(name = "location",
                    column = @Column(name = "location_ckb",     length = 250)),
            @AttributeOverride(name = "collectedBy",
                    column = @Column(name = "collected_by_ckb", length = 250))
    })
    private ImageContent ckbContent;

    // ─── KMR (Kurmanji) Embedded Content ─────────────────────────────────────

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "title",
                    column = @Column(name = "title_kmr",        length = 300)),
            @AttributeOverride(name = "description",
                    column = @Column(name = "description_kmr",  columnDefinition = "TEXT")),
            @AttributeOverride(name = "location",
                    column = @Column(name = "location_kmr",     length = 250)),
            @AttributeOverride(name = "collectedBy",
                    column = @Column(name = "collected_by_kmr", length = 250))
    })
    private ImageContent kmrContent;

    // ─── Image Album ──────────────────────────────────────────────────────────
    //
    // @BatchSize: for 20 collections on a page, Hibernate loads
    // ALL their album items in 1 IN-query instead of 20 queries.

    @Builder.Default
    @BatchSize(size = 50)
    @OneToMany(
            mappedBy = "imageCollection",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    @OrderBy("sortOrder ASC")
    private List<ImageAlbumItem> imageAlbum = new ArrayList<>();

    // ─── Publishment Date ─────────────────────────────────────────────────────

    @Column(name = "publishment_date")
    private LocalDate publishmentDate;

    // ─── Content Languages ────────────────────────────────────────────────────
    //
    // Changed EAGER → LAZY + @BatchSize.
    // Hibernate fires 1 IN-query for all languages across the whole page,
    // instead of loading them inside the main JOIN (N+1 with EAGER).

    @Builder.Default
    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "image_collection_languages",
            joinColumns = @JoinColumn(name = "image_collection_id")
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "language", nullable = false, length = 10)
    private Set<Language> contentLanguages = new LinkedHashSet<>();

    // ─── CKB Tags ─────────────────────────────────────────────────────────────
    //
    // @BatchSize: 1 IN-query loads tags for the entire page of results.

    @Builder.Default
    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "image_tags_ckb",
            joinColumns = @JoinColumn(name = "image_collection_id")
    )
    @Column(name = "tag_ckb", nullable = false, length = 100)
    private Set<String> tagsCkb = new LinkedHashSet<>();

    // ─── KMR Tags ─────────────────────────────────────────────────────────────

    @Builder.Default
    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "image_tags_kmr",
            joinColumns = @JoinColumn(name = "image_collection_id")
    )
    @Column(name = "tag_kmr", nullable = false, length = 100)
    private Set<String> tagsKmr = new LinkedHashSet<>();

    // ─── CKB Keywords ─────────────────────────────────────────────────────────

    @Builder.Default
    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "image_keywords_ckb",
            joinColumns = @JoinColumn(name = "image_collection_id")
    )
    @Column(name = "keyword_ckb", nullable = false, length = 150)
    private Set<String> keywordsCkb = new LinkedHashSet<>();

    // ─── KMR Keywords ─────────────────────────────────────────────────────────

    @Builder.Default
    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "image_keywords_kmr",
            joinColumns = @JoinColumn(name = "image_collection_id")
    )
    @Column(name = "keyword_kmr", nullable = false, length = 150)
    private Set<String> keywordsKmr = new LinkedHashSet<>();

    // ─── Timestamps ───────────────────────────────────────────────────────────

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private boolean featured = false;
    private Integer featuredOrder;

    /** Optional wide picture for the homepage hero; falls back to the cover when null. */
    @Column(name = "feature_image_url", columnDefinition = "TEXT")
    private String featureImageUrl;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ─── Helper Methods ───────────────────────────────────────────────────────

    /**
     * Returns the first non-blank cover URL (ckb → kmr → hover), or null.
     * Useful as a fallback for logs, notifications, thumbnails.
     */
    public String getAnyCoverUrl() {
        if (ckbCoverUrl   != null && !ckbCoverUrl.isBlank())   return ckbCoverUrl;
        if (kmrCoverUrl   != null && !kmrCoverUrl.isBlank())   return kmrCoverUrl;
        if (hoverCoverUrl != null && !hoverCoverUrl.isBlank()) return hoverCoverUrl;
        return null;
    }
}
