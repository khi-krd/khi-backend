package ak.dev.khi_backend.khi_app.repository.service;

import ak.dev.khi_backend.khi_app.model.service.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ServiceRepository extends JpaRepository<Service, Long> {

    // =========================================================================
    // PHASE-1: ID-ONLY QUERIES (lightweight, paginated, index-friendly)
    // =========================================================================

    /**
     * GET ALL — Phase 1.  All services (admin view, includes inactive).
     * Ordered by explicit sortOrder first (nulls last), then recency.
     */
    @Query("SELECT s.id FROM Service s ORDER BY COALESCE(s.sortOrder, 2147483647) ASC, s.publishedAt DESC, s.createdAt DESC")
    Page<Long> findAllIds(Pageable pageable);

    /**
     * GET ALL ACTIVE — Phase 1.  Only active services (public view).
     * Ordered by explicit sortOrder first (nulls last), then recency.
     */
    @Query("SELECT s.id FROM Service s WHERE s.active = true ORDER BY COALESCE(s.sortOrder, 2147483647) ASC, s.publishedAt DESC, s.createdAt DESC")
    Page<Long> findActiveIds(Pageable pageable);

    /**
     * FILTER BY TYPE (active only) — Phase 1.
     */
    @Query("""
            SELECT s.id FROM Service s
            WHERE s.active = true
              AND lower(s.serviceType) = lower(:serviceType)
            ORDER BY COALESCE(s.sortOrder, 2147483647) ASC, s.publishedAt DESC, s.createdAt DESC
            """)
    Page<Long> findActiveIdsByType(@Param("serviceType") String serviceType, Pageable pageable);

    /**
     * FILTER BY TYPE (all, including inactive) — Phase 1.
     */
    @Query("""
            SELECT s.id FROM Service s
            WHERE lower(s.serviceType) = lower(:serviceType)
            ORDER BY COALESCE(s.sortOrder, 2147483647) ASC, s.publishedAt DESC, s.createdAt DESC
            """)
    Page<Long> findIdsByType(@Param("serviceType") String serviceType, Pageable pageable);

    /**
     * GLOBAL SEARCH — Phase 1.
     * One search box that covers: service type, location, content title, content description.
     * Both CKB + KMR languages.
     */
    @Query("""
            SELECT DISTINCT s.id FROM Service s
            LEFT JOIN s.contents c
            WHERE s.active = true
              AND (lower(s.serviceType) LIKE lower(concat('%', :q, '%'))
                OR lower(s.location) LIKE lower(concat('%', :q, '%'))
                OR lower(c.title) LIKE lower(concat('%', :q, '%'))
                OR lower(c.description) LIKE lower(concat('%', :q, '%')))
            ORDER BY s.publishedAt DESC, s.createdAt DESC
            """)
    Page<Long> findIdsByGlobalSearch(@Param("q") String q, Pageable pageable);

    /**
     * ADMIN GLOBAL SEARCH — includes inactive services.
     */
    @Query("""
            SELECT DISTINCT s.id FROM Service s
            LEFT JOIN s.contents c
            WHERE lower(s.serviceType) LIKE lower(concat('%', :q, '%'))
               OR lower(s.location) LIKE lower(concat('%', :q, '%'))
               OR lower(c.title) LIKE lower(concat('%', :q, '%'))
               OR lower(c.description) LIKE lower(concat('%', :q, '%'))
            ORDER BY s.publishedAt DESC, s.createdAt DESC
            """)
    Page<Long> findIdsByAdminSearch(@Param("q") String q, Pageable pageable);

    // =========================================================================
    // PHASE-2: BATCH HYDRATION
    //
    // No @EntityGraph — @BatchSize on entity collections fires automatically:
    //   Q1: SELECT s   FROM services         WHERE id IN (...)
    //   Q2: SELECT ... FROM service_contents WHERE service_id IN (...)
    // =========================================================================

    @Query("SELECT s FROM Service s WHERE s.id IN :ids ORDER BY s.publishedAt DESC, s.createdAt DESC")
    List<Service> findAllByIds(@Param("ids") List<Long> ids);

    // =========================================================================
    // SINGLE LOOKUP — Full eager fetch for single-entity views
    // =========================================================================

    /**
     * Fetch a service together with its bilingual contents in one query to
     * avoid N+1 when building the full response.
     */
    @Query("""
            SELECT DISTINCT s FROM Service s
            LEFT JOIN FETCH s.contents
            WHERE s.id = :id
            """)
    Optional<Service> findByIdWithAll(@Param("id") Long id);

    // =========================================================================
    // SIMPLE QUERIES (non-paginated, backward-compatible)
    // =========================================================================

    /** All active services, ordered by publishedAt DESC (latest first). */
    List<Service> findByActiveTrueOrderByPublishedAtDesc();

    /** Filter active services by type (case-insensitive). */
    List<Service> findByActiveTrueAndServiceTypeIgnoreCaseOrderByPublishedAtDesc(String serviceType);

    /** Count active services (for dashboard). */
    long countByActiveTrue();

    /** Distinct service types currently in the DB. */
    @Query("SELECT DISTINCT s.serviceType FROM Service s ORDER BY s.serviceType")
    List<String> findDistinctServiceTypes();

    // =========================================================================
    // FEATURED (homepage carousel)
    // =========================================================================

    /**
     * All featured services with their bilingual contents fetched in one query.
     *
     * The JOIN FETCH is required: {@code contents} is LAZY and
     * SiteContentService reads the localized title outside the Service module's
     * own transaction boundary.
     */
    @Query("""
            SELECT DISTINCT s FROM Service s
            LEFT JOIN FETCH s.contents
            WHERE s.featured = true
            ORDER BY COALESCE(s.featuredOrder, 2147483647) ASC, s.id DESC
            """)
    List<Service> findFeaturedWithContents();

    /** Feeds the global maxFeaturedSlides cap. */
    long countByFeaturedTrue();

    // =========================================================================
    // NAV ANCHOR UNIQUENESS
    // =========================================================================

    /** True when any service already uses this navAnchorId (case-insensitive). */
    boolean existsByNavAnchorIdIgnoreCase(String navAnchorId);

    /** True when another service (id != given) already uses this navAnchorId. */
    boolean existsByNavAnchorIdIgnoreCaseAndIdNot(String navAnchorId, Long id);
}


