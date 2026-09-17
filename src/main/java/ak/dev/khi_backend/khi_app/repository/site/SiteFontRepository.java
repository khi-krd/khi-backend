package ak.dev.khi_backend.khi_app.repository.site;

import ak.dev.khi_backend.khi_app.model.site.SiteFont;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SiteFontRepository extends JpaRepository<SiteFont, Long> {

    List<SiteFont> findAllByOrderByLanguageAscCreatedAtAsc();
}
