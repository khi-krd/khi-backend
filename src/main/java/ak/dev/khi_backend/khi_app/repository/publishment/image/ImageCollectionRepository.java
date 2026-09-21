package ak.dev.khi_backend.khi_app.repository.publishment.image;

import ak.dev.khi_backend.khi_app.model.publishment.image.ImageCollection;
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
public interface ImageCollectionRepository extends JpaRepository<ImageCollection, Long> {

    // =========================================================================
    // PHASE-1: ID-ONLY QUERIES
    //
    // Each query returns only Long IDs — no collection joins, no Cartesian product.
    // Sorted by newest first (publishment_date DESC, created_at DESC).
    // Hits DB indexes directly → O(log n).
    // =========================================================================

    /**
     * GET ALL — Phase 1
     * Manual sortOrder first (nulls last), then newest publishment/created.
     */
    @Query("""
        SELECT ic.id FROM ImageCollection ic
        ORDER BY COALESCE(ic.sortOrder, 2147483647) ASC,
                 ic.publishmentDate DESC, ic.createdAt DESC
        """)
    Page<Long> findAllIds(Pageable pageable);

    /**
     * FILTER BY TYPE — Phase 1
     * Hits idx_img_collection_type.
     *
     * Example: ?type=GALLERY
     */
    @Query("""
        SELECT ic.id FROM ImageCollection ic
        WHERE ic.collectionType = :type
        ORDER BY COALESCE(ic.sortOrder, 2147483647) ASC,
                 ic.publishmentDate DESC, ic.createdAt DESC
        """)
    Page<Long> findIdsByType(
            @Param("type") ak.dev.khi_backend.khi_app.enums.publishment.ImageCollectionType type,
            Pageable pageable);

    /**
     * TAG SEARCH — Phase 1 (partial match, both CKB + KMR)
     *
     * DB index:
     *   CREATE INDEX idx_img_tag_ckb ON image_tags_ckb(lower(tag_ckb));
     *   CREATE INDEX idx_img_tag_kmr ON image_tags_kmr(lower(tag_kmr));
     */
    @Query("""
        SELECT DISTINCT ic.id FROM ImageCollection ic
        LEFT JOIN ic.tagsCkb tckb
        LEFT JOIN ic.tagsKmr tkmr
        WHERE lower(tckb) LIKE lower(concat('%', :tag, '%'))
           OR lower(tkmr) LIKE lower(concat('%', :tag, '%'))
        ORDER BY ic.publishmentDate DESC, ic.createdAt DESC
        """)
    Page<Long> findIdsByTag(@Param("tag") String tag, Pageable pageable);

    /**
     * KEYWORD SEARCH — Phase 1 (partial match, both CKB + KMR)
     *
     * DB index:
     *   CREATE INDEX idx_img_kw_ckb ON image_keywords_ckb(lower(keyword_ckb));
     *   CREATE INDEX idx_img_kw_kmr ON image_keywords_kmr(lower(keyword_kmr));
     */
    @Query("""
        SELECT DISTINCT ic.id FROM ImageCollection ic
        LEFT JOIN ic.keywordsCkb kckb
        LEFT JOIN ic.keywordsKmr kkmr
        WHERE lower(kckb) LIKE lower(concat('%', :keyword, '%'))
           OR lower(kkmr) LIKE lower(concat('%', :keyword, '%'))
        ORDER BY ic.publishmentDate DESC, ic.createdAt DESC
        """)
    Page<Long> findIdsByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /**
     * GLOBAL SEARCH — Phase 1
     *
     * One search box covering:
     *   title, description, collectedBy, location (CKB + KMR),
     *   tags (CKB + KMR), keywords (CKB + KMR), topic names.
     */
    @Query("""
        SELECT DISTINCT ic.id FROM ImageCollection ic
        LEFT JOIN ic.tagsCkb     tckb
        LEFT JOIN ic.tagsKmr     tkmr
        LEFT JOIN ic.keywordsCkb kckb
        LEFT JOIN ic.keywordsKmr kkmr
        WHERE lower(ic.ckbContent.title)       LIKE lower(concat('%', :q, '%'))
           OR lower(ic.kmrContent.title)       LIKE lower(concat('%', :q, '%'))
           OR lower(ic.ckbContent.description) LIKE lower(concat('%', :q, '%'))
           OR lower(ic.kmrContent.description) LIKE lower(concat('%', :q, '%'))
           OR lower(ic.ckbContent.collectedBy) LIKE lower(concat('%', :q, '%'))
           OR lower(ic.kmrContent.collectedBy) LIKE lower(concat('%', :q, '%'))
           OR lower(ic.ckbContent.location)    LIKE lower(concat('%', :q, '%'))
           OR lower(ic.kmrContent.location)    LIKE lower(concat('%', :q, '%'))
           OR lower(tckb)                      LIKE lower(concat('%', :q, '%'))
           OR lower(tkmr)                      LIKE lower(concat('%', :q, '%'))
           OR lower(kckb)                      LIKE lower(concat('%', :q, '%'))
           OR lower(kkmr)                      LIKE lower(concat('%', :q, '%'))
           OR lower(ic.topic.nameCkb)          LIKE lower(concat('%', :q, '%'))
           OR lower(ic.topic.nameKmr)          LIKE lower(concat('%', :q, '%'))
        ORDER BY ic.publishmentDate DESC, ic.createdAt DESC
        """)
    Page<Long> findIdsByGlobalSearch(@Param("q") String q, Pageable pageable);

    /**
     * TOPIC SEARCH — Phase 1
     */
    @Query("""
        SELECT ic.id FROM ImageCollection ic
        WHERE ic.topic.id = :topicId
        ORDER BY COALESCE(ic.sortOrder, 2147483647) ASC,
                 ic.publishmentDate DESC, ic.createdAt DESC
        """)
    Page<Long> findIdsByTopic(@Param("topicId") Long topicId, Pageable pageable);

    // =========================================================================
    // PHASE-2: BATCH HYDRATION
    //
    // Loads bare ImageCollection rows only.
    // @BatchSize on entity collections fires automatically on first access:
    //
    //   Q1 : SELECT ic  FROM image_collections        WHERE id IN (...)
    //   Q2 : SELECT ... FROM image_collection_languages WHERE image_collection_id IN (...)
    //   Q3 : SELECT ... FROM image_tags_ckb            WHERE image_collection_id IN (...)
    //   Q4 : SELECT ... FROM image_tags_kmr            WHERE image_collection_id IN (...)
    //   Q5 : SELECT ... FROM image_keywords_ckb        WHERE image_collection_id IN (...)
    //   Q6 : SELECT ... FROM image_keywords_kmr        WHERE image_collection_id IN (...)
    //   Q7 : SELECT ... FROM image_album_items         WHERE image_collection_id IN (...)
    //   Q8 : SELECT ... FROM publishment_topics        WHERE id IN (...)  ← @BatchSize on class
    //
    //   8 fast IN-queries for any page size.
    // =========================================================================

    @Query("SELECT ic FROM ImageCollection ic WHERE ic.id IN :ids")
    List<ImageCollection> findAllByIds(@Param("ids") List<Long> ids);

    // =========================================================================
    // SINGLE LOOKUP — @EntityGraph safe (only 1 item, bounded Cartesian)
    // =========================================================================

    @EntityGraph(attributePaths = {
            "contentLanguages",
            "tagsCkb", "tagsKmr",
            "keywordsCkb", "keywordsKmr",
            "imageAlbum", "topic"
    })
    @Query("SELECT ic FROM ImageCollection ic WHERE ic.id = :id")
    Optional<ImageCollection> findByIdWithGraph(@Param("id") Long id);

    @EntityGraph(attributePaths = {
            "contentLanguages", "tagsCkb", "tagsKmr", "keywordsCkb",
            "keywordsKmr", "imageAlbum", "topic"
    })
    Optional<ImageCollection> findBySlugCkbOrSlugKmr(String slugCkb, String slugKmr);


    // NEW — used by SiteContentService.getFeatured()
    List<ImageCollection> findByFeaturedTrueOrderByFeaturedOrderAscIdDesc();

    Page<ImageCollection> findByFeaturedTrue(Pageable pageable);

    long countByFeaturedTrue();



}
