package ak.dev.khi_backend.khi_app.model.news;

import ak.dev.khi_backend.khi_app.enums.Language;
import ak.dev.khi_backend.khi_app.enums.MediaKind;
import ak.dev.khi_backend.khi_app.model.media.MediaItem;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Entity
@Table(
        name = "news",
        indexes = {
                @Index(name = "idx_news_date_published", columnList = "datePublished DESC"),
                @Index(name = "idx_news_created_at",    columnList = "createdAt DESC")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class News {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Card cover asset URL (image, video, or audio).  Originally only an
     * image; kept under the same column for backwards compatibility.  Pair
     * with {@link #coverMediaType} to know how to render it.
     */
    @Column(name = "cover_url", length = 1024)
    private String coverUrl;

    /**
     * Discriminator for {@link #coverUrl}.  Defaults to {@link MediaKind#IMAGE}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "cover_media_type", length = 16)
    @Builder.Default
    private MediaKind coverMediaType = MediaKind.IMAGE;

    /**
     * Optional poster (VIDEO) or cover art (AUDIO) URL for the card cover.
     * Used by the public listing to keep a still preview even when the cover
     * is playable media.
     */
    @Column(name = "cover_thumbnail_url", length = 1024)
    private String coverThumbnailUrl;

    /**
     * Mixed-type gallery (images / videos / audios) rendered beside the cover.
     * Stored as JSONB; admins reorder and re-type entries without schema changes.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "media_gallery", columnDefinition = "jsonb")
    @Builder.Default
    private List<MediaItem> mediaGallery = new ArrayList<>();

    @Column(name = "date_published")
    private LocalDate datePublished;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ─────────────────────────────────────────────
    // Content Languages
    //
    // Changed EAGER → LAZY.
    // @BatchSize: for a page of 20 news, Hibernate fires
    // 1 IN-query for all their languages instead of 20 queries.
    // ─────────────────────────────────────────────

    @Builder.Default
    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "news_content_languages",
            joinColumns = @JoinColumn(name = "news_id")
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "language", nullable = false, length = 10)
    private Set<Language> contentLanguages = new LinkedHashSet<>();

    // ─────────────────────────────────────────────
    // Embedded bilingual content blocks
    // ─────────────────────────────────────────────

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "title",       column = @Column(name = "title_ckb",       length = 250)),
            @AttributeOverride(name = "description", column = @Column(name = "description_ckb", columnDefinition = "TEXT"))
    })
    private NewsContent ckbContent;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "title",       column = @Column(name = "title_kmr",       length = 250)),
            @AttributeOverride(name = "description", column = @Column(name = "description_kmr", columnDefinition = "TEXT"))
    })
    private NewsContent kmrContent;

    // ─────────────────────────────────────────────
    // Tags (simple string collections)
    //
    // Changed EAGER → LAZY + @BatchSize.
    // All 4 tag/keyword collections fire 1 IN-query each
    // for an entire page of results — total: 4 fast queries.
    // ─────────────────────────────────────────────

    @Builder.Default
    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "news_tags_ckb", joinColumns = @JoinColumn(name = "news_id"))
    @Column(name = "tag_ckb", nullable = false, length = 80)
    private Set<String> tagsCkb = new LinkedHashSet<>();

    @Builder.Default
    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "news_tags_kmr", joinColumns = @JoinColumn(name = "news_id"))
    @Column(name = "tag_kmr", nullable = false, length = 80)
    private Set<String> tagsKmr = new LinkedHashSet<>();

    @Builder.Default
    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "news_keywords_ckb", joinColumns = @JoinColumn(name = "news_id"))
    @Column(name = "keyword_ckb", nullable = false, length = 120)
    private Set<String> keywordsCkb = new LinkedHashSet<>();

    @Builder.Default
    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "news_keywords_kmr", joinColumns = @JoinColumn(name = "news_id"))
    @Column(name = "keyword_kmr", nullable = false, length = 120)
    private Set<String> keywordsKmr = new LinkedHashSet<>();

    // ─────────────────────────────────────────────
    // Category & SubCategory
    //
    // LAZY is correct here.
    // @BatchSize is placed on the NewsCategory and
    // NewsSubCategory entity classes — that tells Hibernate
    // to batch-load them with IN-queries when accessed
    // across a page of results (1 query per type, not N).
    // ─────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private NewsCategory category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sub_category_id", nullable = false)
    private NewsSubCategory subCategory;


    private boolean featured = false;
    private Integer featuredOrder;

    /** Optional wide picture for the homepage hero; falls back to the cover when null. */
    @Column(name = "feature_image_url", columnDefinition = "TEXT")
    private String featureImageUrl;

    // Media table dropped — inline images / audio / video / documents now
    // live inside the Tiptap HTML stored in ckbContent.description and
    // kmrContent.description. Uploads go through POST /api/v1/media/upload.



    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.datePublished == null) {
            this.datePublished = LocalDate.now();
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}