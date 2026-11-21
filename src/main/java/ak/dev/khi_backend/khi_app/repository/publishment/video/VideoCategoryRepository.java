package ak.dev.khi_backend.khi_app.repository.publishment.video;

import ak.dev.khi_backend.khi_app.model.publishment.video.VideoCategory;
import ak.dev.khi_backend.khi_app.repository.publishment.writing.ManagedOptionRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VideoCategoryRepository extends ManagedOptionRepository<VideoCategory> {

    @Query("SELECT COUNT(v) FROM Video v WHERE v.category.id = :categoryId")
    long countVideos(@Param("categoryId") Long categoryId);

    @Query("SELECT v.category.id, COUNT(v) FROM Video v WHERE v.category IS NOT NULL GROUP BY v.category.id")
    List<Object[]> countBooksPerOption();

    @Modifying
    @Query("UPDATE Video v SET v.category = null WHERE v.category.id = :categoryId")
    void detachFromBooks(@Param("categoryId") Long categoryId);
}
