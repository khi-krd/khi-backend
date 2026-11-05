package ak.dev.khi_backend.khi_app.repository.site;

import ak.dev.khi_backend.khi_app.model.site.DonationArchiveVideo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DonationArchiveVideoRepository extends JpaRepository<DonationArchiveVideo, Long> {
    /** Website: only the videos an admin has switched on. */
    List<DonationArchiveVideo> findAllByActiveTrueOrderByDisplayOrderAsc();

    /** Dashboard: hidden rows too, so they can be switched back on. */
    List<DonationArchiveVideo> findAllByOrderByDisplayOrderAsc();
}
