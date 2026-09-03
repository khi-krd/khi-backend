package ak.dev.khi_backend.khi_app.repository.publishment.writing;

import ak.dev.khi_backend.khi_app.model.publishment.writing.Writing;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * WritingRepository — Optimised queries for Writing entity.
 *
 * ─── Performance notes ────────────────────────────────────────────────────────
 *
 *  PROBLEM 1 – Cartesian product on tag / keyword search
 *    The old findByTagInBothLanguages / findByKeywordInBothLanguages used
 *    LEFT JOIN on two element-collection tables simultaneously, generating a
 *    cross-product row set before DISTINCT could filter it.
 *    SOLUTION → EXISTS sub-queries: each collection is checked independently,
 *    zero row multiplication, no DISTINCT scan over thousands of joined rows.
 *
 *  PROBLEM 2 – N+1 on single-entity fetch
 *    findById() returns a bare Writing; Hibernate then fires 5 extra SELECTs
 *    for each EAGER @ElementCollection + 1 more for the lazy seriesBooks.
 *    SOLUTION → @EntityGraph on findByIdWithDetails(); Hibernate uses
 *    JOIN FETCH for seriesBooks, parentBook and topic in one round-trip.
 *    The EAGER sets benefit from @BatchSize(size=25) on Writing.java.
 *
 *  PROBLEM 3 – Slow COUNT on paginated queries
 *    Spring Data reruns the full JOIN/EXISTS query just to count rows.
 *    Every paginated method now has a separated countQuery that only
 *    touches the primary writings table.
 *
 *  STRONGLY RECOMMENDED — add to Writing.java element collections (already done):
 *
 *    @BatchSize(size = 25)
 *    @ElementCollection(fetch = FetchType.EAGER)
 *    private Set<String> tagsCkb = ...;
 *    // repeat for tagsCkb, tagsKmr, keywordsCkb, keywordsKmr, contentLanguages
 *
 * ──────────────────────────────────────────────────────────────────────────────
 */
@Repository
public interface WritingRepository extends JpaRepository<Writing, Long> {

    // ═══════════════════════════════════════════════════════════════════════════
    // ── SINGLE ENTITY FETCH ── (detail page / getWritingById)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Full detail fetch — all associations loaded in the fewest round-trips.
     *
     * @EntityGraph JOIN FETCHes: seriesBooks (LAZY) + parentBook (LAZY) + topic (LAZY)
     * Element collections (EAGER) use Hibernate batch select via @BatchSize.
     *
     * Use this instead of plain findById() everywhere a Response DTO is returned.
     */
    @EntityGraph(attributePaths = {"seriesBooks", "parentBook", "topic"})
    @Query("SELECT w FROM Writing w WHERE w.id = :id")
    Optional<Writing> findByIdWithDetails(@Param("id") Long id);

    // ═══════════════════════════════════════════════════════════════════════════
    // ── PAGINATED LIST ── (admin list / public browse)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * All writings, paged — topic eagerly joined to avoid N+1 on topic name
     * display. Separated countQuery hits only the writings table (no JOIN).
     */
    @Query(
            value      = "SELECT w FROM Writing w LEFT JOIN FETCH w.topic",
            countQuery = "SELECT COUNT(w) FROM Writing w"
    )
    Page<Writing> findAllWithTopic(Pageable pageable);

    // ═══════════════════════════════════════════════════════════════════════════
    // ── SERIES QUERIES ──
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find all books in a series ordered by seriesOrder ASC.
     * O(log n + k) — uses idx_series_composite (series_id, series_order).
     */
    @Query("SELECT w FROM Writing w WHERE w.seriesId = :seriesId ORDER BY w.seriesOrder ASC")
    List<Writing> findBySeriesIdOrderBySeriesOrderAsc(@Param("seriesId") String seriesId);

    /**
     * Find series root books (parentBook IS NULL and has a seriesId), paged.
     * Separated countQuery avoids re-running the ORDER BY on the count pass.
     */
    @Query(
            value      = "SELECT w FROM Writing w WHERE w.parentBook IS NULL AND w.seriesId IS NOT NULL ORDER BY w.createdAt DESC",
            countQuery = "SELECT COUNT(w) FROM Writing w WHERE w.parentBook IS NULL AND w.seriesId IS NOT NULL"
    )
    Page<Writing> findSeriesParents(Pageable pageable);

    /**
     * Count books in a series — O(log n) via idx_series_id.
     */
    @Query("SELECT COUNT(w) FROM Writing w WHERE w.seriesId = :seriesId")
    Long countBySeriesId(@Param("seriesId") String seriesId);

    /**
     * Find the max seriesOrder in a series for auto-incrementing new entries.
     * O(log n) via idx_series_composite.
     */
    @Query("SELECT COALESCE(MAX(w.seriesOrder), 0.0) FROM Writing w WHERE w.seriesId = :seriesId")
    Double findMaxSeriesOrder(@Param("seriesId") String seriesId);

    // ═══════════════════════════════════════════════════════════════════════════
    // ── WRITER SEARCH ── (single collection column — no JOIN problem here)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Search by writer name in CKB only.
     * O(log n) — uses idx_writer_ckb.
     */
    @Query(
            value      = "SELECT w FROM Writing w WHERE LOWER(w.ckbContent.writer) LIKE LOWER(CONCAT('%', :writer, '%'))",
            countQuery = "SELECT COUNT(w) FROM Writing w WHERE LOWER(w.ckbContent.writer) LIKE LOWER(CONCAT('%', :writer, '%'))"
    )
    Page<Writing> findByWriterCkbContainingIgnoreCase(@Param("writer") String writer, Pageable pageable);

    /**
     * Search by writer name in KMR only.
     * O(log n) — uses idx_writer_kmr.
     */
    @Query(
            value      = "SELECT w FROM Writing w WHERE LOWER(w.kmrContent.writer) LIKE LOWER(CONCAT('%', :writer, '%'))",
            countQuery = "SELECT COUNT(w) FROM Writing w WHERE LOWER(w.kmrContent.writer) LIKE LOWER(CONCAT('%', :writer, '%'))"
    )
    Page<Writing> findByWriterKmrContainingIgnoreCase(@Param("writer") String writer, Pageable pageable);

    /**
     * Search by writer name in BOTH languages.
     * Two indexed column checks with OR — no collection JOIN, no DISTINCT needed.
     */
    @Query(
            value      = "SELECT w FROM Writing w " +
                    "WHERE LOWER(w.ckbContent.writer) LIKE LOWER(CONCAT('%', :writer, '%')) " +
                    "   OR LOWER(w.kmrContent.writer) LIKE LOWER(CONCAT('%', :writer, '%'))",
            countQuery = "SELECT COUNT(w) FROM Writing w " +
                    "WHERE LOWER(w.ckbContent.writer) LIKE LOWER(CONCAT('%', :writer, '%')) " +
                    "   OR LOWER(w.kmrContent.writer) LIKE LOWER(CONCAT('%', :writer, '%'))"
    )
    Page<Writing> findByWriterInBothLanguages(@Param("writer") String writer, Pageable pageable);

    // ═══════════════════════════════════════════════════════════════════════════
    // ── TAG SEARCH ── (EXISTS replaces LEFT JOIN + DISTINCT)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Search by CKB tag only.
     * Single JOIN — no cross-product risk, separated countQuery.
     */
    @Query(
            value      = "SELECT w FROM Writing w WHERE EXISTS (" +
                    "  SELECT t FROM Writing w2 JOIN w2.tagsCkb t " +
                    "  WHERE w2 = w AND LOWER(t) LIKE LOWER(CONCAT('%', :tag, '%')))",
            countQuery = "SELECT COUNT(w) FROM Writing w WHERE EXISTS (" +
                    "  SELECT t FROM Writing w2 JOIN w2.tagsCkb t " +
                    "  WHERE w2 = w AND LOWER(t) LIKE LOWER(CONCAT('%', :tag, '%')))"
    )
    Page<Writing> findByTagCkb(@Param("tag") String tag, Pageable pageable);

    /**
     * Search by KMR tag only.
     */
    @Query(
            value      = "SELECT w FROM Writing w WHERE EXISTS (" +
                    "  SELECT t FROM Writing w2 JOIN w2.tagsKmr t " +
                    "  WHERE w2 = w AND LOWER(t) LIKE LOWER(CONCAT('%', :tag, '%')))",
            countQuery = "SELECT COUNT(w) FROM Writing w WHERE EXISTS (" +
                    "  SELECT t FROM Writing w2 JOIN w2.tagsKmr t " +
                    "  WHERE w2 = w AND LOWER(t) LIKE LOWER(CONCAT('%', :tag, '%')))"
    )
    Page<Writing> findByTagKmr(@Param("tag") String tag, Pageable pageable);

    /**
     * Search by tag in BOTH languages.
     * EXISTS sub-queries — each collection checked independently,
     * no cross-product, no DISTINCT scan over thousands of joined rows.
     */
    @Query(
            value = """
            SELECT w FROM Writing w
            WHERE EXISTS (
                SELECT t FROM Writing w2 JOIN w2.tagsCkb t
                WHERE w2 = w AND LOWER(t) LIKE LOWER(CONCAT('%', :tag, '%'))
            )
            OR EXISTS (
                SELECT t FROM Writing w2 JOIN w2.tagsKmr t
                WHERE w2 = w AND LOWER(t) LIKE LOWER(CONCAT('%', :tag, '%'))
            )
            """,
            countQuery = """
            SELECT COUNT(w) FROM Writing w
            WHERE EXISTS (
                SELECT t FROM Writing w2 JOIN w2.tagsCkb t
                WHERE w2 = w AND LOWER(t) LIKE LOWER(CONCAT('%', :tag, '%'))
            )
            OR EXISTS (
                SELECT t FROM Writing w2 JOIN w2.tagsKmr t
                WHERE w2 = w AND LOWER(t) LIKE LOWER(CONCAT('%', :tag, '%'))
            )
            """
    )
    Page<Writing> findByTagInBothLanguages(@Param("tag") String tag, Pageable pageable);

    // ═══════════════════════════════════════════════════════════════════════════
    // ── KEYWORD SEARCH ── (EXISTS replaces LEFT JOIN + DISTINCT)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Search by CKB keyword only.
     */
    @Query(
            value      = "SELECT w FROM Writing w WHERE EXISTS (" +
                    "  SELECT k FROM Writing w2 JOIN w2.keywordsCkb k " +
                    "  WHERE w2 = w AND LOWER(k) LIKE LOWER(CONCAT('%', :keyword, '%')))",
            countQuery = "SELECT COUNT(w) FROM Writing w WHERE EXISTS (" +
                    "  SELECT k FROM Writing w2 JOIN w2.keywordsCkb k " +
                    "  WHERE w2 = w AND LOWER(k) LIKE LOWER(CONCAT('%', :keyword, '%')))"
    )
    Page<Writing> findByKeywordCkb(@Param("keyword") String keyword, Pageable pageable);

    /**
     * Search by KMR keyword only.
     */
    @Query(
            value      = "SELECT w FROM Writing w WHERE EXISTS (" +
                    "  SELECT k FROM Writing w2 JOIN w2.keywordsKmr k " +
                    "  WHERE w2 = w AND LOWER(k) LIKE LOWER(CONCAT('%', :keyword, '%')))",
            countQuery = "SELECT COUNT(w) FROM Writing w WHERE EXISTS (" +
                    "  SELECT k FROM Writing w2 JOIN w2.keywordsKmr k " +
                    "  WHERE w2 = w AND LOWER(k) LIKE LOWER(CONCAT('%', :keyword, '%')))"
    )
    Page<Writing> findByKeywordKmr(@Param("keyword") String keyword, Pageable pageable);

    /**
     * Search by keyword in BOTH languages.
     * EXISTS sub-queries — no cross-product, no DISTINCT scan.
     */
    @Query(
            value = """
            SELECT w FROM Writing w
            WHERE EXISTS (
                SELECT k FROM Writing w2 JOIN w2.keywordsCkb k
                WHERE w2 = w AND LOWER(k) LIKE LOWER(CONCAT('%', :keyword, '%'))
            )
            OR EXISTS (
                SELECT k FROM Writing w2 JOIN w2.keywordsKmr k
                WHERE w2 = w AND LOWER(k) LIKE LOWER(CONCAT('%', :keyword, '%'))
            )
            """,
            countQuery = """
            SELECT COUNT(w) FROM Writing w
            WHERE EXISTS (
                SELECT k FROM Writing w2 JOIN w2.keywordsCkb k
                WHERE w2 = w AND LOWER(k) LIKE LOWER(CONCAT('%', :keyword, '%'))
            )
            OR EXISTS (
                SELECT k FROM Writing w2 JOIN w2.keywordsKmr k
                WHERE w2 = w AND LOWER(k) LIKE LOWER(CONCAT('%', :keyword, '%'))
            )
            """
    )
    Page<Writing> findByKeywordInBothLanguages(@Param("keyword") String keyword, Pageable pageable);

    // ═══════════════════════════════════════════════════════════════════════════
// ADD THESE TWO METHODS to your existing WritingRepository.java
//
// They follow the exact Phase-1 / Phase-2 pattern used by every other
// repository in the project (ProjectRepository, NewsRepository, etc.)
// ═══════════════════════════════════════════════════════════════════════════

    // ── GLOBAL SEARCH — Phase 1 (ID-only, no entity hydration) ───────────────

    /**
     * Global search across titles, descriptions, writer names,
     * tags (CKB + KMR), and keywords (CKB + KMR).
     *
     * Returns DISTINCT IDs only — no entity loading, no Cartesian product.
     * Sorted newest first (id DESC as a proxy for creation date).
     *
     * DB indexes that help this query:
     *   idx_writer_ckb, idx_writer_kmr (already defined on Writing.java)
     *   (add functional indexes on lower(tag) / lower(keyword) for further gains)
     */
    @Query("""
        SELECT DISTINCT w.id FROM Writing w
        LEFT JOIN w.tagsCkb     tckb
        LEFT JOIN w.tagsKmr     tkmr
        LEFT JOIN w.keywordsCkb kckb
        LEFT JOIN w.keywordsKmr kkmr
        WHERE lower(w.ckbContent.title)       LIKE lower(concat('%', :q, '%'))
           OR lower(w.kmrContent.title)       LIKE lower(concat('%', :q, '%'))
           OR lower(w.ckbContent.description) LIKE lower(concat('%', :q, '%'))
           OR lower(w.kmrContent.description) LIKE lower(concat('%', :q, '%'))
           OR lower(w.ckbContent.writer)      LIKE lower(concat('%', :q, '%'))
           OR lower(w.kmrContent.writer)      LIKE lower(concat('%', :q, '%'))
           OR lower(tckb)                     LIKE lower(concat('%', :q, '%'))
           OR lower(tkmr)                     LIKE lower(concat('%', :q, '%'))
           OR lower(kckb)                     LIKE lower(concat('%', :q, '%'))
           OR lower(kkmr)                     LIKE lower(concat('%', :q, '%'))
        ORDER BY w.id DESC
        """)
    Page<Long> findIdsByGlobalSearch(@Param("q") String q, Pageable pageable);

    // ── BATCH HYDRATION — Phase 2 ─────────────────────────────────────────────

    /**
     * Load bare Writing rows for a list of IDs.
     * No @EntityGraph here — collections are LAZY and never accessed
     * by the GlobalSearchService (it only needs scalar fields).
     */
    @Query("SELECT w FROM Writing w WHERE w.id IN :ids")
    List<Writing> findAllByIds(@Param("ids") List<Long> ids);


    // NEW — used by SiteContentService.getFeatured(). Same join as above, scoped to featured
    // records and ordered by featuredOrder instead of recency.
    @Query("select w from Writing w left join fetch w.topic where w.featured = true order by w.featuredOrder asc, w.id desc")
    List<Writing> findFeaturedWithTopic();

    @Query(
            value = "select w from Writing w left join fetch w.topic where w.featured = true",
            countQuery = "select count(w) from Writing w where w.featured = true"
    )
    Page<Writing> findFeaturedWithTopic(Pageable pageable);

    long countByFeaturedTrue();

}
