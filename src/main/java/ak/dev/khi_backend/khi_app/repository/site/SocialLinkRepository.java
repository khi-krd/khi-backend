package ak.dev.khi_backend.khi_app.repository.site;

import ak.dev.khi_backend.khi_app.model.site.SocialLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SocialLinkRepository extends JpaRepository<SocialLink, Long> {
    /** Website: only the links an admin has switched on. */
    List<SocialLink> findAllByActiveTrueOrderByDisplayOrderAsc();

    /** Dashboard: hidden rows too, so they can be switched back on. */
    List<SocialLink> findAllByOrderByDisplayOrderAsc();
}
