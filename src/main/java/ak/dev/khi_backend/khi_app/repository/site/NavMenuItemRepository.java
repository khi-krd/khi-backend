package ak.dev.khi_backend.khi_app.repository.site;

import ak.dev.khi_backend.khi_app.model.site.NavMenuItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NavMenuItemRepository extends JpaRepository<NavMenuItem, Long> {

    List<NavMenuItem> findAllByActiveTrueOrderByDisplayOrderAscIdAsc();

    List<NavMenuItem> findAllByOrderByDisplayOrderAscIdAsc();
}
