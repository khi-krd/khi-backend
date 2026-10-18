package ak.dev.khi_backend.khi_app.repository.news;

import ak.dev.khi_backend.khi_app.model.news.NewsCategory;
import ak.dev.khi_backend.khi_app.model.news.NewsSubCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NewsSubCategoryRepository extends JpaRepository<NewsSubCategory, Long> {

    /**
     * Find subcategory by category and CKB name
     */
    Optional<NewsSubCategory> findByCategoryAndNameCkb(NewsCategory category, String nameCkb);

    /**
     * Find subcategory by category and KMR name
     */
    Optional<NewsSubCategory> findByCategoryAndNameKmr(NewsCategory category, String nameKmr);

    List<NewsSubCategory> findByCategory_IdOrderByNameCkbAsc(Long categoryId);
}