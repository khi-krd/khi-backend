package ak.dev.khi_backend.khi_app.repository.contact;

import ak.dev.khi_backend.khi_app.model.contact.OfficeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OfficeTypeRepository extends JpaRepository<OfficeType, Long> {

    Optional<OfficeType> findByNameCkb(String nameCkb);

    List<OfficeType> findAllByOrderByNameCkbAsc();
}
