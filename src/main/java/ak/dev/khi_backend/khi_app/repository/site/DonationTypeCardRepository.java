package ak.dev.khi_backend.khi_app.repository.site;

import ak.dev.khi_backend.khi_app.model.site.DonationTypeCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DonationTypeCardRepository extends JpaRepository<DonationTypeCard, Long> {
    /** Website: only the cards an admin has switched on. */
    List<DonationTypeCard> findAllByActiveTrueOrderByDisplayOrderAsc();

    /** Dashboard: hidden rows too, so they can be switched back on. */
    List<DonationTypeCard> findAllByOrderByDisplayOrderAsc();
}
