package ak.dev.khi_backend.khi_app.model.publishment.writing;

import ak.dev.khi_backend.khi_app.enums.Language;
import ak.dev.khi_backend.khi_app.enums.publishment.BookGenre;
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
 * ─── Book Genres (ElementCollection → writing_book_genres) ───────────────────
 *
 *  A book can belong to MULTIPLE genres (e.g. a historical novel = HISTORY + NOVEL).
 *  Stored in a separate collection table: writing_book_genres (writing_id, book_genre).
 *  @BatchSize(size = 25) for consistent batch-loading with other collections.
 *
 * ─── DB Migration (from single book_genre column to collection table) ────────
 *
 *  -- 1. Create new collection table
 *  CREATE TABLE IF NOT EXISTS writing_book_genres (
 *      writing_id BIGINT NOT NULL REFERENCES writings(id) ON DELETE CASCADE,
 *      book_genre VARCHAR(30) NOT NULL,
 *      PRIMARY KEY (writing_id, book_genre)
 *  );
 *  CREATE INDEX idx_wbg_genre ON writing_book_genres (book_genre);
 *
 *  -- 2. Migrate existing single-genre data into the new table
 *  INSERT INTO writing_book_genres (writing_id, book_genre)
 *  SELECT id, book_genre FROM writings WHERE book_genre IS NOT NULL
 *  ON CONFLICT DO NOTHING;
 *
 *  -- 3. Drop old single-genre column (after verifying migration)
 *  ALTER TABLE writings DROP COLUMN IF EXISTS book_genre;
 *  DROP INDEX IF EXISTS idx_writing_genre;
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
     * Stored in collection table: writing_book_genres (writing_id, book_genre).
     *
     * At least one genre is required — enforced by the service layer.
     */
    @BatchSize(size = 25)
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "writing_book_genres",
            joinColumns = @JoinColumn(name = "writing_id")
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "book_genre", nullable = false, length = 30)
    private Set<BookGenre> bookGenres = new LinkedHashSet<>();

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

    /**
     * Check if this book has a specific genre.
     */
    public boolean hasGenre(BookGenre genre) {
        return bookGenres != null && bookGenres.contains(genre);
    }

    /**
     * Backward-compatible: returns the first genre or null.
     * Useful for logs, fallback display, etc.
     */
    public BookGenre getPrimaryGenre() {
        if (bookGenres == null || bookGenres.isEmpty()) return null;
        return bookGenres.iterator().next();
    }
}