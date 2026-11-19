package ak.dev.khi_backend.khi_app.repository.publishment.writing;

import ak.dev.khi_backend.khi_app.model.publishment.writing.Edition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EditionRepository extends ManagedOptionRepository<Edition> {

    @Query("SELECT COUNT(w) FROM Writing w WHERE w.edition.id = :editionId")
    long countBooks(@Param("editionId") Long editionId);

    @Query("SELECT w.edition.id, COUNT(w) FROM Writing w WHERE w.edition IS NOT NULL GROUP BY w.edition.id")
    List<Object[]> countBooksPerOption();

    @Modifying
    @Query("UPDATE Writing w SET w.edition = null WHERE w.edition.id = :editionId")
    void detachFromBooks(@Param("editionId") Long editionId);
}
