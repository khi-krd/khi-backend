package ak.dev.khi_backend.khi_app.model.publishment.writing;

import ak.dev.khi_backend.khi_app.enums.Language;
import ak.dev.khi_backend.khi_app.model.publishment.topic.PublishmentTopic;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

/**
 * Writing/Book entity — bilingual (CKB & KMR) with Series support.
 *
 * ─── Cover Images (3 slots — entity level, mirrors ImageCollection) ──────────
 *
 *  ckbCoverUrl   → Sorani   cover  (column: ckb_cover_url)
 *  kmrCoverUrl   → Kurmanji cover  (column: kmr_cover_url)
 *  hoverCoverUrl → hover overlay   (column: hover_cover_url)
 *
 * ─── Book Genres (ManyToMany → book_genre_links) ─────────────────────────────
 *
 *  A book can belong to MULTIPLE genres (e.g. a historical novel = HISTORY + NOVEL).
 *  Genres are editor-managed BookGenre rows; the link table is
 *  book_genre_links (book_id, genre_id). The old enum collection table
 *  writing_book_genres is frozen — BookGenreSeeder migrated its rows into
 *  links at first boot, and the table is only kept until the website's switch
 *  to dynamic genres is confirmed.
 *
 * ─── Performance ──────────────────────────────────────────────────────────────
 *  @BatchSize(size = 25) on every @ElementCollection:
 *    Without it, loading a page of 20 writings fires 20×6 = 120 extra SELECTs.
 *    With it, Hibernate loads all 6 collections for the whole page in 6 queries
 *    using WHERE writing_id IN (..., ..., ...) — a 20× reduction.
 */
@Entity
@Table(
        name = "writings",
        indexes = {
                @Index(name = "idx_writing_topic_id",   columnList = "topic_id"),
                @Index(name = "idx_writing_institute",  columnList = "published_by_institute"),
                @Index(name = "idx_writing_created_at", columnList = "created_at"),
                @Index(name = "idx_writing_updated_at", columnList = "updated_at"),
                @Index(name = "idx_writer_ckb",         columnList = "writer_ckb"),
                @Index(name = "idx_writer_kmr",         columnList = "writer_kmr"),
                @Index(name = "idx_series_id",          columnList = "series_id"),
                @Index(name = "idx_series_composite",   columnList = "series_id, series_order"),
                @Index(name = "idx_parent_book",        columnList = "parent_book_id")
        }
)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Writing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ─── Cover Images (3 slots) ───────────────────────────────────────────────

    @Column(name = "ckb_cover_url", columnDefinition = "TEXT")
    private String ckbCoverUrl;

    @Column(name = "kmr_cover_url", columnDefinition = "TEXT")
    private String kmrCoverUrl;

    @Column(name = "hover_cover_url", columnDefinition = "TEXT")
    private String hoverCoverUrl;

    // ─── Book Genres (multiple) ───────────────────────────────────────────────

    /**
     * A book can belong to multiple genres (e.g. HISTORY + NOVEL for a historical novel).
     * Genres are editor-managed {@link BookGenre} rows, linked through
     * book_genre_links (book_id, genre_id). At least one genre is required —
     * enforced by the service layer.
     *
     * The old enum {@code @ElementCollection} table writing_book_genres is kept
     * in the database untouched as a frozen pre-migration snapshot (see
     * BookGenreSeeder); nothing reads or writes it anymore, and it can be
     * dropped once the website's switch to dynamic genres is confirmed.
     */
    @BatchSize(size = 25)
    @Builder.Default
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "book_genre_links",
            joinColumns = @JoinColumn(name = "book_id"),
            inverseJoinColumns = @JoinColumn(name = "genre_id")
    )
    @OrderBy("displayOrder ASC, id ASC")
    private Set<BookGenre> genres = new LinkedHashSet<>();

    // ─── Topic ────────────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "topic_id")
    private PublishmentTopic topic;

    // ─── Series / Edition Support ─────────────────────────────────────────────

    @Column(name = "series_id", length = 100)
    private String seriesId;

    @Column(name = "series_name", length = 300)
    private String seriesName;

    @Column(name = "series_order")
    private Double seriesOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_book_id")
    private Writing parentBook;

    @OneToMany(mappedBy = "parentBook", fetch = FetchType.LAZY)
    @OrderBy("seriesOrder ASC")
    @Builder.Default
    private List<Writing> seriesBooks = new ArrayList<>();

    @Column(name = "series_total_books")
    private Integer seriesTotalBooks;

    // ─── Bilingual Support ────────────────────────────────────────────────────

    @BatchSize(size = 25)
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "writing_content_languages",
            joinColumns = @JoinColumn(name = "writing_id")
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "language", nullable = false, length = 10)
    private Set<Language> contentLanguages = new LinkedHashSet<>();

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "title",         column = @Column(name = "title_ckb",           length = 300)),
            @AttributeOverride(name = "description",   column = @Column(name = "description_ckb",     columnDefinition = "TEXT")),
            @AttributeOverride(name = "writer",        column = @Column(name = "writer_ckb",           length = 200)),
            @AttributeOverride(name = "fileUrl",       column = @Column(name = "file_url_ckb",         length = 1000)),
            @AttributeOverride(name = "fileFormat",    column = @Column(name = "file_format_ckb",      length = 20)),
            @AttributeOverride(name = "fileSizeBytes", column = @Column(name = "file_size_bytes_ckb")),
            @AttributeOverride(name = "pageCount",     column = @Column(name = "page_count_ckb")),
            @AttributeOverride(name = "genre",         column = @Column(name = "genre_ckb",            length = 150))
    })
    private WritingContent ckbContent;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "title",         column = @Column(name = "title_kmr",           length = 300)),
            @AttributeOverride(name = "description",   column = @Column(name = "description_kmr",     columnDefinition = "TEXT")),
            @AttributeOverride(name = "writer",        column = @Column(name = "writer_kmr",           length = 200)),
            @AttributeOverride(name = "fileUrl",       column = @Column(name = "file_url_kmr",         length = 1000)),
            @AttributeOverride(name = "fileFormat",    column = @Column(name = "file_format_kmr",      length = 20)),
            @AttributeOverride(name = "fileSizeBytes", column = @Column(name = "file_size_bytes_kmr")),
            @AttributeOverride(name = "pageCount",     column = @Column(name = "page_count_kmr")),
            @AttributeOverride(name = "genre",         column = @Column(name = "genre_kmr",            length = 150))
    })
    private WritingContent kmrContent;

    // ─── Shared Fields ────────────────────────────────────────────────────────

    @Column(name = "published_by_institute", nullable = false)
    private boolean publishedByInstitute;

    // ─── Bilingual Keywords ───────────────────────────────────────────────────

    @BatchSize(size = 25)
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "writing_keywords_ckb", joinColumns = @JoinColumn(name = "writing_id"))
    @Column(name = "keyword_ckb", nullable = false, length = 120)
    private Set<String> keywordsCkb = new LinkedHashSet<>();

    @BatchSize(size = 25)
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "writing_keywords_kmr", joinColumns = @JoinColumn(name = "writing_id"))
    @Column(name = "keyword_kmr", nullable = false, length = 120)
    private Set<String> keywordsKmr = new LinkedHashSet<>();

    // ─── Bilingual Tags ───────────────────────────────────────────────────────

    @BatchSize(size = 25)
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "writing_tags_ckb", joinColumns = @JoinColumn(name = "writing_id"))
    @Column(name = "tag_ckb", nullable = false, length = 80)
    private Set<String> tagsCkb = new LinkedHashSet<>();

    @BatchSize(size = 25)
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "writing_tags_kmr", joinColumns = @JoinColumn(name = "writing_id"))
    @Column(name = "tag_kmr", nullable = false, length = 80)
    private Set<String> tagsKmr = new LinkedHashSet<>();

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
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (seriesId == null)    seriesId    = "series-" + System.currentTimeMillis();
        if (seriesOrder == null) seriesOrder = 1.0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    public boolean isPartOfSeries() {
        return seriesId != null && (seriesTotalBooks == null || seriesTotalBooks > 1);
    }

    public boolean isSeriesParent() {
        return parentBook == null && isPartOfSeries();
    }

    public String getEffectiveSeriesName() {
        if (seriesName != null && !seriesName.isBlank())         return seriesName;
        if (ckbContent != null && ckbContent.getTitle() != null) return ckbContent.getTitle();
        if (kmrContent != null && kmrContent.getTitle() != null) return kmrContent.getTitle();
        return "Unknown Series";
    }

    public String getAnyCoverUrl() {
        if (ckbCoverUrl   != null && !ckbCoverUrl.isBlank())   return ckbCoverUrl;
        if (kmrCoverUrl   != null && !kmrCoverUrl.isBlank())   return kmrCoverUrl;
        if (hoverCoverUrl != null && !hoverCoverUrl.isBlank()) return hoverCoverUrl;
        return null;
    }

}