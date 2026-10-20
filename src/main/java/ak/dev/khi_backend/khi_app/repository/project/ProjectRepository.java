package ak.dev.khi_backend.khi_app.repository.project;

import ak.dev.khi_backend.khi_app.model.project.Project;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    // ============================================================
    // PHASE-1: ID-ONLY QUERIES
    // ============================================================

    @Query("SELECT p.id FROM Project p ORDER BY p.id DESC")
    Page<Long> findAllIds(Pageable pageable);

    @Query("""
        SELECT DISTINCT p.id FROM Project p
        LEFT JOIN p.tagsCkb tckb
        LEFT JOIN p.tagsKmr tkmr
        WHERE lower(tckb.name) LIKE lower(concat('%', :tag, '%'))
           OR lower(tkmr.name) LIKE lower(concat('%', :tag, '%'))
        ORDER BY p.id DESC
        """)
    Page<Long> findIdsByTag(@Param("tag") String tag, Pageable pageable);

    @Query("""
        SELECT DISTINCT p.id FROM Project p
        LEFT JOIN p.keywordsCkb kckb
        LEFT JOIN p.keywordsKmr kkmr
        WHERE lower(kckb.name) LIKE lower(concat('%', :keyword, '%'))
           OR lower(kkmr.name) LIKE lower(concat('%', :keyword, '%'))
        ORDER BY p.id DESC
        """)
    Page<Long> findIdsByKeyword(@Param("keyword") String keyword, Pageable pageable);

    @Query("""
        SELECT DISTINCT p.id FROM Project p
        LEFT JOIN p.tagsCkb     tckb
        LEFT JOIN p.tagsKmr     tkmr
        LEFT JOIN p.keywordsCkb kckb
        LEFT JOIN p.keywordsKmr kkmr
        WHERE lower(p.ckbContent.title)       LIKE lower(concat('%', :q, '%'))
           OR lower(p.kmrContent.title)       LIKE lower(concat('%', :q, '%'))
           OR lower(p.ckbContent.description) LIKE lower(concat('%', :q, '%'))
           OR lower(p.kmrContent.description) LIKE lower(concat('%', :q, '%'))
           OR lower(tckb.name)                LIKE lower(concat('%', :q, '%'))
           OR lower(tkmr.name)                LIKE lower(concat('%', :q, '%'))
           OR lower(kckb.name)                LIKE lower(concat('%', :q, '%'))
           OR lower(kkmr.name)                LIKE lower(concat('%', :q, '%'))
        ORDER BY p.id DESC
        """)
    Page<Long> findIdsByGlobalSearch(@Param("q") String q, Pageable pageable);

    // ============================================================
    // PHASE-2: BATCH HYDRATION
    // ============================================================

    @Query("SELECT p FROM Project p WHERE p.id IN :ids")
    List<Project> findAllByIds(@Param("ids") List<Long> ids);

    // ============================================================
    // SINGLE LOOKUP — @EntityGraph
    // ============================================================

    @EntityGraph(attributePaths = {
            "tagsCkb",     "tagsKmr",
            "keywordsCkb", "keywordsKmr",
            "contentLanguages"
    })
    @Query("SELECT p FROM Project p WHERE p.id = :id")
    Optional<Project> findByIdWithGraph(@Param("id") Long id);

    List<Project> findByFeaturedTrueOrderByFeaturedOrderAscIdDesc();

    Page<Project> findByFeaturedTrue(Pageable pageable);

    long countByFeaturedTrue();

    // ── Project-type vocabulary: usage counts + rename propagation ───────
    long countByProjectTypeCkb(String projectTypeCkb);

    /** Projects carrying the type as primary or anywhere in their selection. */
    @Query("select count(distinct p) from Project p "
            + "left join p.projectTypes pt "
            + "where lower(p.projectTypeCkb) = lower(:ckb) "
            + "   or lower(pt.typeCkb) = lower(:ckb)")
    long countProjectsUsingType(@Param("ckb") String ckb);

    @Modifying
    @Query("update Project p set p.projectTypeCkb = :ckb, p.projectTypeKmr = :kmr "
            + "where p.projectTypeCkb = :oldCkb")
    int renameType(@Param("oldCkb") String oldCkb,
                   @Param("ckb") String ckb,
                   @Param("kmr") String kmr);

    /** Renames the type inside the project_type_selections collection. */
    @Modifying
    @Query(value = "update project_type_selections set type_ckb = :ckb, type_kmr = :kmr "
            + "where lower(type_ckb) = lower(:oldCkb)", nativeQuery = true)
    int renameTypeInSelections(@Param("oldCkb") String oldCkb,
                               @Param("ckb") String ckb,
                               @Param("kmr") String kmr);
}
