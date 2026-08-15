package ak.dev.khi_backend.khi_app.repository.site;

import ak.dev.khi_backend.khi_app.model.site.NavMenuItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NavMenuItemRepository extends JpaRepository<NavMenuItem, Long> {

    /**
     * {@code @EntityGraph} pulls the links in the same query — {@code open-in-view}
     * is off, so a lazy list touched after the transaction closes would blow up.
     */
    @EntityGraph(attributePaths = "links")
    List<NavMenuItem> findAllByActiveTrueOrderByDisplayOrderAscIdAsc();

    @EntityGraph(attributePaths = "links")
    List<NavMenuItem> findAllByOrderByDisplayOrderAscIdAsc();

    @Override
    @EntityGraph(attributePaths = "links")
    Optional<NavMenuItem> findById(Long id);

    boolean existsByItemKeyIgnoreCase(String itemKey);

    boolean existsByItemKeyIgnoreCaseAndIdNot(String itemKey, Long id);
}
