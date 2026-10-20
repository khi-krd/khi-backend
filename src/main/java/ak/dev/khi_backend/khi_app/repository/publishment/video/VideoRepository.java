package ak.dev.khi_backend.khi_app.repository.publishment.video;

import ak.dev.khi_backend.khi_app.model.publishment.video.Video;
import ak.dev.khi_backend.khi_app.model.publishment.video.VideoType;
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
 * VideoRepository — Optimised queries for Video entity.
 *
 * ─── Performance notes ────────────────────────────────────────────────────────
 *
 *  PROBLEM 1 – Cartesian product on search
 *    The old searchByKeyword / searchByTag used LEFT JOIN across FIVE element-
 *    collection tables simultaneously, producing a huge cross-product row set.
 *    SOLUTION → EXISTS sub-queries: each collection is checked independently;
 *    no row multiplication, no DISTINCT pass over thousands of joined rows.
 *
 *  PROBLEM 2 – N+1 on single-entity fetch
 *    findById() returns a bare Video; Hibernate then fires 5 separate SELECTs
 *    for each EAGER @ElementCollection + 1 more for the lazy videoClipItems.
 *    SOLUTION → @EntityGraph on the detail fetch; Hibernate uses JOIN FETCH for
 *    videoClipItems and topic in one round-trip. The EAGER sets still use
 *    secondary selects, but see note below about @BatchSize.
 *
 *  PROBLEM 3 – Slow COUNT query on paginated search
 *    Spring Data by default runs the same heavy JOIN as the data query just to
 *    count rows. With a separated countQuery it only hits the primary table.
 *
 *  STRONGLY RECOMMENDED – add to Video.java element collections:
 *
 *    @BatchSize(size = 25)          // ← import org.hibernate.annotations.BatchSize
 *    @ElementCollection(fetch = FetchType.EAGER)
 *    private Set<String> tagsCkb = ...;
 *    // repeat for tagsCkb, tagsKmr, keywordsCkb, keywordsKmr, contentLanguages
 *
 *  With @BatchSize, Hibernate loads all five sets in 5 total queries for an
 *  entire page (instead of 5 × pageSize queries).
 *
 * ──────────────────────────────────────────────────────────────────────────────
 */
@Repository
public interface VideoRepository extends JpaRepository<Video, Long> {

    // ═══════════════════════════════════════════════════════════════════════════
    // ── SINGLE ENTITY FETCH ── (detail page / getVideoById)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Full detail fetch — all associations loaded in the fewest round-trips.
     *
     * @EntityGraph JOIN FETCHes: videoClipItems (LAZY) + topic (LAZY)
     * Element collections (EAGER) use Hibernate batch select if @BatchSize is set.
     *
     * Use this instead of plain findById() everywhere a VideoDTO is returned.
     */
    @EntityGraph(attributePaths = {"videoClipItems", "topics"})
    @Query("SELECT v FROM Video v WHERE v.id = :id")
    Optional<Video> findByIdWithDetails(@Param("id") Long id);

    // ═══════════════════════════════════════════════════════════════════════════
    // ── PAGINATED LIST ── (admin list / public browse)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * All videos, paged — topic eagerly joined to avoid N+1 on topic name
     * display. Separated countQuery hits only the videos table (no JOIN).
     */
    @Query(
            value      = "SELECT v FROM Video v",
            countQuery = "SELECT COUNT(v) FROM Video v"
    )
    Page<Video> findAllWithTopic(Pageable pageable);

    /**
     * All videos of a specific type, paged.
     * e.g. findAllByType(VideoType.FILM, pageable)
     */
    @Query(
            value      = "SELECT v FROM Video v WHERE v.videoType = :type",
            countQuery = "SELECT COUNT(v) FROM Video v WHERE v.videoType = :type"
    )
    Page<Video> findAllByType(@Param("type") VideoType type, Pageable pageable);

    /**
     * All VIDEO_CLIP videos filtered by album flag, paged.
     */
    @Query(
            value      = "SELECT v FROM Video v " +
                    "WHERE v.videoType = ak.dev.khi_backend.khi_app.model.publishment.video.VideoType.VIDEO_CLIP " +
                    "  AND v.albumOfMemories = :album",
            countQuery = "SELECT COUNT(v) FROM Video v " +
                    "WHERE v.videoType = ak.dev.khi_backend.khi_app.model.publishment.video.VideoType.VIDEO_CLIP " +
                    "  AND v.albumOfMemories = :album"
    )
    Page<Video> findAllClipsByAlbumFlag(@Param("album") boolean album, Pageable pageable);

    /**
     * All videos for a topic, paged.
     */
    @Query(
            value      = "SELECT v FROM Video v JOIN v.topics t WHERE t.id = :topicId",
            countQuery = "SELECT COUNT(DISTINCT v) FROM Video v JOIN v.topics t WHERE t.id = :topicId"
    )
    Page<Video> findAllByTopicId(@Param("topicId") Long topicId, Pageable pageable);

    // ═══════════════════════════════════════════════════════════════════════════
    // ── KEYWORD SEARCH ── (EXISTS replaces LEFT JOIN + DISTINCT)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Full-text keyword search across titles, descriptions, directors,
     * and both language keyword collections.
     *
     * Uses EXISTS sub-queries so each collection is scanned independently —
     * no row-multiplication, no DISTINCT over a Cartesian product.
     * The countQuery is isolated to avoid re-running the heavy EXISTS checks
     * just to produce a count.
     */
    @Query(
            value = """
            SELECT v FROM Video v
            WHERE LOWER(v.ckbContent.title)       LIKE LOWER(CONCAT('%', :kw, '%'))
               OR LOWER(v.kmrContent.title)       LIKE LOWER(CONCAT('%', :kw, '%'))
               OR LOWER(v.ckbContent.description) LIKE LOWER(CONCAT('%', :kw, '%'))
               OR LOWER(v.kmrContent.description) LIKE LOWER(CONCAT('%', :kw, '%'))
               OR LOWER(v.ckbContent.director)    LIKE LOWER(CONCAT('%', :kw, '%'))
               OR LOWER(v.kmrContent.director)    LIKE LOWER(CONCAT('%', :kw, '%'))
               OR EXISTS (
                   SELECT kc FROM Video v2 JOIN v2.keywordsCkb kc
                   WHERE v2 = v AND LOWER(kc) LIKE LOWER(CONCAT('%', :kw, '%'))
               )
               OR EXISTS (
                   SELECT km FROM Video v2 JOIN v2.keywordsKmr km
                   WHERE v2 = v AND LOWER(km) LIKE LOWER(CONCAT('%', :kw, '%'))
               )
            """,
            countQuery = """
            SELECT COUNT(v) FROM Video v
            WHERE LOWER(v.ckbContent.title)       LIKE LOWER(CONCAT('%', :kw, '%'))
               OR LOWER(v.kmrContent.title)       LIKE LOWER(CONCAT('%', :kw, '%'))
               OR LOWER(v.ckbContent.description) LIKE LOWER(CONCAT('%', :kw, '%'))
               OR LOWER(v.kmrContent.description) LIKE LOWER(CONCAT('%', :kw, '%'))
               OR LOWER(v.ckbContent.director)    LIKE LOWER(CONCAT('%', :kw, '%'))
               OR LOWER(v.kmrContent.director)    LIKE LOWER(CONCAT('%', :kw, '%'))
               OR EXISTS (
                   SELECT kc FROM Video v2 JOIN v2.keywordsCkb kc
                   WHERE v2 = v AND LOWER(kc) LIKE LOWER(CONCAT('%', :kw, '%'))
               )
               OR EXISTS (
                   SELECT km FROM Video v2 JOIN v2.keywordsKmr km
                   WHERE v2 = v AND LOWER(km) LIKE LOWER(CONCAT('%', :kw, '%'))
               )
            """
    )
    Page<Video> searchByKeyword(@Param("kw") String keyword, Pageable pageable);

    // ═══════════════════════════════════════════════════════════════════════════
    // ── TAG SEARCH ── (EXISTS replaces LEFT JOIN + DISTINCT)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Tag search across both CKB and KMR tag collections.
     * EXISTS sub-queries — no cross-join, no DISTINCT scan.
     */
    @Query(
            value = """
            SELECT v FROM Video v
            WHERE EXISTS (
                SELECT tc FROM Video v2 JOIN v2.tagsCkb tc
                WHERE v2 = v AND LOWER(tc) LIKE LOWER(CONCAT('%', :tag, '%'))
            )
            OR EXISTS (
                SELECT tm FROM Video v2 JOIN v2.tagsKmr tm
                WHERE v2 = v AND LOWER(tm) LIKE LOWER(CONCAT('%', :tag, '%'))
            )
            """,
            countQuery = """
            SELECT COUNT(v) FROM Video v
            WHERE EXISTS (
                SELECT tc FROM Video v2 JOIN v2.tagsCkb tc
                WHERE v2 = v AND LOWER(tc) LIKE LOWER(CONCAT('%', :tag, '%'))
            )
            OR EXISTS (
                SELECT tm FROM Video v2 JOIN v2.tagsKmr tm
                WHERE v2 = v AND LOWER(tm) LIKE LOWER(CONCAT('%', :tag, '%'))
            )
            """
    )
    Page<Video> searchByTag(@Param("tag") String tag, Pageable pageable);

    // ═══════════════════════════════════════════════════════════════════════════
    // ── TOPIC UTILITIES ──
    // ═══════════════════════════════════════════════════════════════════════════

    /** Used by deleteTopic() to un-link all videos before topic removal. */
    @Query("SELECT DISTINCT v FROM Video v JOIN v.topics t WHERE t.id = :topicId")
    List<Video> findByTopicId(@Param("topicId") Long topicId);


    // ═══════════════════════════════════════════════════════════════════════════
// ADD THESE TWO METHODS to your existing VideoRepository.java
//
// They follow the exact Phase-1 / Phase-2 pattern used by every other
// repository in the project (ProjectRepository, NewsRepository, etc.)
// ═══════════════════════════════════════════════════════════════════════════

    // ── GLOBAL SEARCH — Phase 1 (ID-only, no entity hydration) ───────────────

    /**
     * Global search across titles, descriptions, director names,
     * tags (CKB + KMR), and keywords (CKB + KMR).
     *
     * Returns DISTINCT IDs only — no entity loading, no Cartesian product.
     * Sorted by newest first (id DESC as a proxy for creation date).
     *
     * DB indexes that help this query:
     *   idx_video_title_ckb, idx_video_title_kmr
     *   (add functional indexes on lower(tag) / lower(keyword) for further gains)
     */
    @Query("""
        SELECT DISTINCT v.id FROM Video v
        LEFT JOIN v.tagsCkb     tckb
        LEFT JOIN v.tagsKmr     tkmr
        LEFT JOIN v.keywordsCkb kckb
        LEFT JOIN v.keywordsKmr kkmr
        WHERE lower(v.ckbContent.title)       LIKE lower(concat('%', :q, '%'))
           OR lower(v.kmrContent.title)       LIKE lower(concat('%', :q, '%'))
           OR lower(v.ckbContent.description) LIKE lower(concat('%', :q, '%'))
           OR lower(v.kmrContent.description) LIKE lower(concat('%', :q, '%'))
           OR lower(v.ckbContent.director)    LIKE lower(concat('%', :q, '%'))
           OR lower(v.kmrContent.director)    LIKE lower(concat('%', :q, '%'))
           OR lower(tckb)                     LIKE lower(concat('%', :q, '%'))
           OR lower(tkmr)                     LIKE lower(concat('%', :q, '%'))
           OR lower(kckb)                     LIKE lower(concat('%', :q, '%'))
           OR lower(kkmr)                     LIKE lower(concat('%', :q, '%'))
        ORDER BY v.id DESC
        """)
    Page<Long> findIdsByGlobalSearch(@Param("q") String q, Pageable pageable);

    // ── BATCH HYDRATION — Phase 2 ─────────────────────────────────────────────

    /**
     * Load bare Video rows for a list of IDs.
     * No @EntityGraph here — collections are LAZY and never accessed
     * by the GlobalSearchService (it only needs scalar fields).
     * ORDER BY FIELD preserves the Phase-1 sort order.
     */
    @Query("SELECT v FROM Video v WHERE v.id IN :ids")
    List<Video> findAllByIds(@Param("ids") List<Long> ids);

    // NEW — used by SiteContentService.getFeatured(). Same join as above, scoped to featured
    // records and ordered by featuredOrder instead of recency.
    @Query("select distinct v from Video v left join fetch v.topics where v.featured = true order by v.featuredOrder asc, v.id desc")
    List<Video> findFeaturedWithTopic();

    @Query(
            value = "select v from Video v where v.featured = true",
            countQuery = "select count(v) from Video v where v.featured = true"
    )
    Page<Video> findFeaturedWithTopic(Pageable pageable);

    long countByFeaturedTrue();


}
