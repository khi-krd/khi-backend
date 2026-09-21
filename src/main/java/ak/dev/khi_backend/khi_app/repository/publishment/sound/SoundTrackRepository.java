package ak.dev.khi_backend.khi_app.repository.publishment.sound;

import ak.dev.khi_backend.khi_app.model.publishment.sound.SoundTrack;
import ak.dev.khi_backend.khi_app.enums.publishment.TrackState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SoundTrackRepository extends JpaRepository<SoundTrack, Long> {

    // =========================================================================
    // PHASE-1 — ID-ONLY QUERIES
    //
    // Each query returns only Long IDs — no collection joins, no Cartesian product.
    // Sorted newest-first (created_at DESC).
    // Hits DB indexes directly → O(log n).
    // =========================================================================

    /**
     * GET ALL — Phase 1
     * Manual sortOrder first (nulls last), newest-first as fallback.
     */
    @Query("""
        SELECT s.id FROM SoundTrack s
        ORDER BY COALESCE(s.sortOrder, 2147483647) ASC, s.createdAt DESC
        """)
    Page<Long> findAllIds(Pageable pageable);

    /**
     * FILTER BY TRACK STATE (SINGLE / MULTI) — Phase 1
     * Hits idx_soundtrack_state.
     */
    @Query("""
        SELECT s.id FROM SoundTrack s
        WHERE s.trackState = :state
        ORDER BY COALESCE(s.sortOrder, 2147483647) ASC, s.createdAt DESC
        """)
    Page<Long> findIdsByState(
            @Param("state") TrackState state,
            Pageable pageable);

    /**
     * FILTER BY SOUND TYPE — Phase 1
     * Hits idx_soundtrack_type.
     */
    @Query("""
        SELECT s.id FROM SoundTrack s
        WHERE lower(s.soundType) = lower(:soundType)
        ORDER BY COALESCE(s.sortOrder, 2147483647) ASC, s.createdAt DESC
        """)
    Page<Long> findIdsBySoundType(
            @Param("soundType") String soundType,
            Pageable pageable);

    /**
     * FILTER BY TOPIC — Phase 1
     * Hits idx_soundtrack_topic.
     */
    @Query("""
        SELECT s.id FROM SoundTrack s
        WHERE s.topic.id = :topicId
        ORDER BY COALESCE(s.sortOrder, 2147483647) ASC, s.createdAt DESC
        """)
    Page<Long> findIdsByTopic(
            @Param("topicId") Long topicId,
            Pageable pageable);

    /**
     * FILTER — Album of Memories only — Phase 1
     * Hits idx_soundtrack_album.
     */
    @Query("""
        SELECT s.id FROM SoundTrack s
        WHERE s.albumOfMemories = true
        ORDER BY COALESCE(s.sortOrder, 2147483647) ASC, s.createdAt DESC
        """)
    Page<Long> findIdsAlbumOfMemories(Pageable pageable);

    /**
     * TAG SEARCH (partial match, both CKB + KMR) — Phase 1
     *
     * Recommended DB indexes:
     *   CREATE INDEX idx_st_tag_ckb ON sound_track_tags_ckb(lower(tag_ckb));
     *   CREATE INDEX idx_st_tag_kmr ON sound_track_tags_kmr(lower(tag_kmr));
     */
    @Query("""
        SELECT s.id FROM SoundTrack s
        LEFT JOIN s.tagsCkb tckb
        LEFT JOIN s.tagsKmr tkmr
        WHERE lower(tckb) LIKE lower(concat('%', :tag, '%'))
           OR lower(tkmr) LIKE lower(concat('%', :tag, '%'))
        GROUP BY s.id
        ORDER BY max(s.createdAt) DESC
        """)
    Page<Long> findIdsByTag(
            @Param("tag") String tag,
            Pageable pageable);

    /**
     * KEYWORD SEARCH (partial match, both CKB + KMR) — Phase 1
     *
     * Recommended DB indexes:
     *   CREATE INDEX idx_st_kw_ckb ON sound_track_keywords_ckb(lower(keyword_ckb));
     *   CREATE INDEX idx_st_kw_kmr ON sound_track_keywords_kmr(lower(keyword_kmr));
     */
    @Query("""
        SELECT s.id FROM SoundTrack s
        LEFT JOIN s.keywordsCkb kckb
        LEFT JOIN s.keywordsKmr kkmr
        WHERE lower(kckb) LIKE lower(concat('%', :keyword, '%'))
           OR lower(kkmr) LIKE lower(concat('%', :keyword, '%'))
        GROUP BY s.id
        ORDER BY max(s.createdAt) DESC
        """)
    Page<Long> findIdsByKeyword(
            @Param("keyword") String keyword,
            Pageable pageable);

    /**
     * GLOBAL SEARCH — Phase 1
     *
     * One search box covering:
     *   title (CKB + KMR), description (CKB + KMR),
     *   tags (CKB + KMR), keywords (CKB + KMR),
     *   album name, terms, topic names.
     */
    @Query("""
        SELECT s.id FROM SoundTrack s
        LEFT JOIN s.tagsCkb     tckb
        LEFT JOIN s.tagsKmr     tkmr
        LEFT JOIN s.keywordsCkb kckb
        LEFT JOIN s.keywordsKmr kkmr
        WHERE lower(s.ckbContent.title)       LIKE lower(concat('%', :q, '%'))
           OR lower(s.kmrContent.title)       LIKE lower(concat('%', :q, '%'))
           OR lower(s.ckbContent.description) LIKE lower(concat('%', :q, '%'))
           OR lower(s.kmrContent.description) LIKE lower(concat('%', :q, '%'))
           OR lower(s.albumName)              LIKE lower(concat('%', :q, '%'))
           OR lower(s.terms)                  LIKE lower(concat('%', :q, '%'))
           OR lower(tckb)                     LIKE lower(concat('%', :q, '%'))
           OR lower(tkmr)                     LIKE lower(concat('%', :q, '%'))
           OR lower(kckb)                     LIKE lower(concat('%', :q, '%'))
           OR lower(kkmr)                     LIKE lower(concat('%', :q, '%'))
           OR lower(s.topic.nameCkb)          LIKE lower(concat('%', :q, '%'))
           OR lower(s.topic.nameKmr)          LIKE lower(concat('%', :q, '%'))
        GROUP BY s.id
        ORDER BY max(s.createdAt) DESC
        """)
    Page<Long> findIdsByGlobalSearch(
            @Param("q") String q,
            Pageable pageable);

    // =========================================================================
    // PHASE-2 — BATCH HYDRATION
    //
    // Loads bare SoundTrack rows only.
    // @BatchSize on entity collections fires automatically on first access:
    //
    //   Q1  : SELECT s   FROM sound_tracks                   WHERE id IN (...)
    //   Q2  : SELECT ... FROM sound_track_content_languages   WHERE sound_track_id IN (...)
    //   Q3  : SELECT ... FROM sound_track_locations           WHERE sound_track_id IN (...)
    //   Q4  : SELECT ... FROM sound_track_readers             WHERE sound_track_id IN (...)
    //   Q5  : SELECT ... FROM sound_track_directors           WHERE sound_track_id IN (...)
    //   Q6  : SELECT ... FROM sound_track_keywords_ckb        WHERE sound_track_id IN (...)
    //   Q7  : SELECT ... FROM sound_track_keywords_kmr        WHERE sound_track_id IN (...)
    //   Q8  : SELECT ... FROM sound_track_tags_ckb            WHERE sound_track_id IN (...)
    //   Q9  : SELECT ... FROM sound_track_tags_kmr            WHERE sound_track_id IN (...)
    //   Q10 : SELECT ... FROM sound_track_files               WHERE sound_track_id IN (...)
    //   Q11 : SELECT ... FROM sound_track_attachments         WHERE sound_track_id IN (...)
    //   Q12 : SELECT ... FROM publishment_topics              WHERE id IN (...)  ← @BatchSize on class
    //
    //   12 fast IN-queries for any page size.
    // =========================================================================

    @Query("SELECT s FROM SoundTrack s WHERE s.id IN :ids")
    List<SoundTrack> findAllByIds(@Param("ids") List<Long> ids);

    // =========================================================================
    // SINGLE LOOKUP — @EntityGraph safe (only 1 item, bounded Cartesian)
    //
    // Used by getById(), update(), delete().
    // =========================================================================

    @EntityGraph(attributePaths = {
            "contentLanguages",
            "reader",
            "directors",
            "locations",
            "tagsCkb", "tagsKmr",
            "keywordsCkb", "keywordsKmr",
            "files",
            "attachments",
            "topic"
    })
    @Query("SELECT s FROM SoundTrack s WHERE s.id = :id")
    Optional<SoundTrack> findByIdWithGraph(@Param("id") Long id);


    // NEW — used by SiteContentService.getFeatured()
    List<SoundTrack> findByFeaturedTrueOrderByFeaturedOrderAscIdDesc();

    Page<SoundTrack> findByFeaturedTrue(Pageable pageable);

    long countByFeaturedTrue();

}
