package ak.dev.khi_backend.khi_app.repository.site;

import ak.dev.khi_backend.khi_app.model.site.Location;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LocationRepository extends JpaRepository<Location, Long> {

    Optional<Location> findByNameCkbIgnoreCase(String nameCkb);

    List<Location> findAllByOrderByDisplayOrderAscIdAsc();

    @Query("select coalesce(max(l.displayOrder), 0) from Location l")
    int findMaxDisplayOrder();
}
