package ak.dev.khi_backend.khi_app.repository.about;

import ak.dev.khi_backend.khi_app.model.about.About;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AboutRepository extends JpaRepository<About, Long> {

    // ─── Single slug lookups (for uniqueness validation) ──────────────────────

    Optional<About> findBySlugCkb(String slugCkb);

    Optional<About> findBySlugKmr(String slugKmr);

    // ─── Public lookup: match either slug ─────────────────────────────────────

    /**
     * Find a page by its CKB slug OR its KMR slug.
     * Used by the public-facing {@code getBySlug(slug)} endpoint so callers
     * can pass whichever language slug they have.
     */
    Optional<About> findBySlugCkbOrSlugKmr(String slugCkb, String slugKmr);

    Page<About> findAllByActiveTrueOrderByDisplayOrderAsc(Pageable pageable);

    // ─── Featured (leads the public About page) ───────────────────────────────

    /**
     * Featured About pages in highlight order — lowest featuredOrder first, nulls last via the
     * id tiebreak. The first row is the record that LEADS /about; any others render as
     * highlighted sections below it.
     *
     * Not used by the hero carousel: About pages are page-level highlights and take no share of
     * SiteSettings.maxFeaturedSlides. Callers today filter the paginated list instead; this is
     * the query a dedicated GET /api/v1/about/featured would use.
     */
    List<About> findByFeaturedTrueOrderByFeaturedOrderAscIdDesc();

    // ─── Existence checks ─────────────────────────────────────────────────────

    boolean existsBySlugCkb(String slugCkb);

    boolean existsBySlugKmr(String slugKmr);
}
