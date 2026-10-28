package ak.dev.khi_backend.khi_app.repository.news;

import ak.dev.khi_backend.khi_app.model.news.News;
import ak.dev.khi_backend.khi_app.model.news.NewsCategory;
import ak.dev.khi_backend.khi_app.model.news.NewsSubCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NewsRepository extends JpaRepository<News, Long> {

    // ============================================================
    // PHASE-1: ID-ONLY QUERIES
    //
    // Lightweight, paginated, index-friendly.
    // No joins on collection tables = no Cartesian product.
    // Sorted by datePublished DESC, createdAt DESC (newest first).
    // ============================================================

    /**
     * GET ALL — Phase 1
     * Manual sortOrder first (nulls last), then the previous default
     * (newest published) as tiebreak.
     */
    @Query("""
        SELECT n.id FROM News n
        ORDER BY COALESCE(n.sortOrder, 2147483647) ASC,
                 n.datePublished DESC, n.createdAt DESC
        """)
    Page<Long> findAllIds(Pageable pageable);

    /**
     * TAG SEARCH — Phase 1 (partial match, both languages)
     *
     * Searches tagsCkb AND tagsKmr in one query.
     * Use language param in service to restrict if needed.
     *
     * DB index: CREATE INDEX idx_news_tag_ckb ON news_tags_ckb(lower(tag_ckb));
     *           CREATE INDEX idx_news_tag_kmr ON news_tags_kmr(lower(tag_kmr));
     */
    @Query("""
        SELECT DISTINCT n.id FROM News n
        LEFT JOIN n.tagsCkb tckb
        LEFT JOIN n.tagsKmr tkmr
        WHERE lower(tckb) LIKE lower(concat('%', :tag, '%'))
           OR lower(tkmr) LIKE lower(concat('%', :tag, '%'))
        ORDER BY COALESCE(n.sortOrder, 2147483647) ASC,
                 n.datePublished DESC, n.createdAt DESC
        """)
    Page<Long> findIdsByTag(@Param("tag") String tag, Pageable pageable);

    /**
     * TAG SEARCH — CKB only — Phase 1
     */
    @Query("""
        SELECT DISTINCT n.id FROM News n
        LEFT JOIN n.tagsCkb tckb
        WHERE lower(tckb) LIKE lower(concat('%', :tag, '%'))
        ORDER BY COALESCE(n.sortOrder, 2147483647) ASC,
                 n.datePublished DESC, n.createdAt DESC
        """)
    Page<Long> findIdsByTagCkb(@Param("tag") String tag, Pageable pageable);

    /**
     * TAG SEARCH — KMR only — Phase 1
     */
    @Query("""
        SELECT DISTINCT n.id FROM News n
        LEFT JOIN n.tagsKmr tkmr
        WHERE lower(tkmr) LIKE lower(concat('%', :tag, '%'))
        ORDER BY COALESCE(n.sortOrder, 2147483647) ASC,
                 n.datePublished DESC, n.createdAt DESC
        """)
    Page<Long> findIdsByTagKmr(@Param("tag") String tag, Pageable pageable);

    /**
     * KEYWORD SEARCH — Phase 1 (both languages)
     *
     * DB index: CREATE INDEX idx_news_kw_ckb ON news_keywords_ckb(lower(keyword_ckb));
     *           CREATE INDEX idx_news_kw_kmr ON news_keywords_kmr(lower(keyword_kmr));
     */
    @Query("""
        SELECT DISTINCT n.id FROM News n
        LEFT JOIN n.keywordsCkb kckb
        LEFT JOIN n.keywordsKmr kkmr
        WHERE lower(kckb) LIKE lower(concat('%', :keyword, '%'))
           OR lower(kkmr) LIKE lower(concat('%', :keyword, '%'))
        ORDER BY COALESCE(n.sortOrder, 2147483647) ASC,
                 n.datePublished DESC, n.createdAt DESC
        """)
    Page<Long> findIdsByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /**
     * KEYWORD SEARCH — CKB only — Phase 1
     */
    @Query("""
        SELECT DISTINCT n.id FROM News n
        LEFT JOIN n.keywordsCkb kckb
        WHERE lower(kckb) LIKE lower(concat('%', :keyword, '%'))
        ORDER BY COALESCE(n.sortOrder, 2147483647) ASC,
                 n.datePublished DESC, n.createdAt DESC
        """)
    Page<Long> findIdsByKeywordCkb(@Param("keyword") String keyword, Pageable pageable);

    /**
     * KEYWORD SEARCH — KMR only — Phase 1
     */
    @Query("""
        SELECT DISTINCT n.id FROM News n
        LEFT JOIN n.keywordsKmr kkmr
        WHERE lower(kkmr) LIKE lower(concat('%', :keyword, '%'))
        ORDER BY COALESCE(n.sortOrder, 2147483647) ASC,
                 n.datePublished DESC, n.createdAt DESC
        """)
    Page<Long> findIdsByKeywordKmr(@Param("keyword") String keyword, Pageable pageable);

    /**
     * GLOBAL SEARCH — Phase 1
     *
     * One search box that covers everything:
     * title, description, tags, keywords — both CKB + KMR.
     * Returns DISTINCT IDs: manual sortOrder first, newest as tiebreak.
     */
    @Query("""
        SELECT DISTINCT n.id FROM News n
        LEFT JOIN n.tagsCkb     tckb
        LEFT JOIN n.tagsKmr     tkmr
        LEFT JOIN n.keywordsCkb kckb
        LEFT JOIN n.keywordsKmr kkmr
        WHERE lower(n.ckbContent.title)       LIKE lower(concat('%', :q, '%'))
           OR lower(n.kmrContent.title)       LIKE lower(concat('%', :q, '%'))
           OR lower(n.ckbContent.description) LIKE lower(concat('%', :q, '%'))
           OR lower(n.kmrContent.description) LIKE lower(concat('%', :q, '%'))
           OR lower(tckb)                     LIKE lower(concat('%', :q, '%'))
           OR lower(tkmr)                     LIKE lower(concat('%', :q, '%'))
           OR lower(kckb)                     LIKE lower(concat('%', :q, '%'))
           OR lower(kkmr)                     LIKE lower(concat('%', :q, '%'))
        ORDER BY COALESCE(n.sortOrder, 2147483647) ASC,
                 n.datePublished DESC, n.createdAt DESC
        """)
    Page<Long> findIdsByGlobalSearch(@Param("q") String q, Pageable pageable);

    /**
     * CATEGORY SEARCH — Phase 1
     * Manual sortOrder first (nulls last), then newest published.
     */
    @Query("""
        SELECT n.id FROM News n
        WHERE lower(n.category.nameCkb) LIKE lower(concat('%', :name, '%'))
           OR lower(n.category.nameKmr) LIKE lower(concat('%', :name, '%'))
        ORDER BY COALESCE(n.sortOrder, 2147483647) ASC,
                 n.datePublished DESC, n.createdAt DESC
        """)
    Page<Long> findIdsByCategory(@Param("name") String name, Pageable pageable);

    /**
     * SUBCATEGORY SEARCH — Phase 1
     * Manual sortOrder first (nulls last), then newest published.
     */
    @Query("""
        SELECT n.id FROM News n
        WHERE lower(n.subCategory.nameCkb) LIKE lower(concat('%', :name, '%'))
           OR lower(n.subCategory.nameKmr) LIKE lower(concat('%', :name, '%'))
        ORDER BY COALESCE(n.sortOrder, 2147483647) ASC,
                 n.datePublished DESC, n.createdAt DESC
        """)
    Page<Long> findIdsBySubCategory(@Param("name") String name, Pageable pageable);

    // ============================================================
    // PHASE-2: BATCH HYDRATION — NO @EntityGraph here.
    //
    // @BatchSize on entity collections fires automatically:
    //
    //   Query 1 : SELECT n   FROM news                WHERE id IN (...)
    //   Query 2 : SELECT ... FROM news_content_languages WHERE news_id IN (...)
    //   Query 3 : SELECT ... FROM news_tags_ckb          WHERE news_id IN (...)
    //   Query 4 : SELECT ... FROM news_tags_kmr          WHERE news_id IN (...)
    //   Query 5 : SELECT ... FROM news_keywords_ckb      WHERE news_id IN (...)
    //   Query 6 : SELECT ... FROM news_keywords_kmr      WHERE news_id IN (...)
    //   Query 7 : SELECT ... FROM news_media             WHERE news_id IN (...)
    //   Query 8 : SELECT ... FROM news_categories        WHERE id IN (...)  ← @BatchSize on NewsCategory class
    //   Query 9 : SELECT ... FROM news_sub_categories    WHERE id IN (...)  ← @BatchSize on NewsSubCategory class
    //
    //   9 fast IN-queries for any page size.
    // ============================================================

    @Query("""
        SELECT n FROM News n WHERE n.id IN :ids
        ORDER BY COALESCE(n.sortOrder, 2147483647) ASC,
                 n.datePublished DESC, n.createdAt DESC
        """)
    List<News> findAllByIds(@Param("ids") List<Long> ids);

    // ============================================================
    // SINGLE LOOKUP — @EntityGraph safe here (1 item, bounded)
    // ============================================================

    @EntityGraph(attributePaths = {
            "contentLanguages",
            "tagsCkb", "tagsKmr",
            "keywordsCkb", "keywordsKmr",
            "category", "subCategory"
    })
    @Query("SELECT n FROM News n WHERE n.id = :id")
    Optional<News> findByIdWithGraph(@Param("id") Long id);




    List<News> findByFeaturedTrueOrderByFeaturedOrderAscIdDesc();

    Page<News> findByFeaturedTrue(Pageable pageable);

    long countByFeaturedTrue();

    // ── Taxonomy management: usage counts + safe reassign on delete ──────
    long countByCategory_Id(Long categoryId);

    long countBySubCategory_Id(Long subCategoryId);

    @Modifying
    @Query("update News n set n.category = :to where n.category.id = :fromId")
    int reassignCategory(@Param("fromId") Long fromId, @Param("to") NewsCategory to);

    @Modifying
    @Query("update News n set n.subCategory = :to where n.subCategory.id = :fromId")
    int reassignSubCategory(@Param("fromId") Long fromId, @Param("to") NewsSubCategory to);

}
