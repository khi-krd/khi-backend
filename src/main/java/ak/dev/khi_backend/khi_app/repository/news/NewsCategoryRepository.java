package ak.dev.khi_backend.khi_app.repository.news;

import ak.dev.khi_backend.khi_app.model.news.NewsCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NewsCategoryRepository extends JpaRepository<NewsCategory, Long> {

    /**
     * Find category by CKB name
     */
    Optional<NewsCategory> findByNameCkb(String nameCkb);

    /**
     * Find category by KMR name
     */
    Optional<NewsCategory> findByNameKmr(String nameKmr);

    List<NewsCategory> findAllByOrderByNameCkbAsc();
}