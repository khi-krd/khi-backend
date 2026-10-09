package ak.dev.khi_backend.khi_app.repository.site;

import ak.dev.khi_backend.khi_app.model.site.StaticText;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StaticTextRepository extends JpaRepository<StaticText, Long> {

    List<StaticText> findByLocaleOrderByKeyAsc(String locale);

    Optional<StaticText> findByKeyAndLocale(String key, String locale);

    List<StaticText> findAllByOrderByKeyAsc();

    boolean existsByKeyAndLocale(String key, String locale);
}
