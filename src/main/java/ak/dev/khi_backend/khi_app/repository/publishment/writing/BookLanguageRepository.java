package ak.dev.khi_backend.khi_app.repository.publishment.writing;

import ak.dev.khi_backend.khi_app.model.publishment.writing.BookLanguage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BookLanguageRepository extends ManagedOptionRepository<BookLanguage> {

    @Query("SELECT COUNT(w) FROM Writing w WHERE w.bookLanguage.id = :languageId")
    long countBooks(@Param("languageId") Long languageId);

    @Query("SELECT w.bookLanguage.id, COUNT(w) FROM Writing w WHERE w.bookLanguage IS NOT NULL GROUP BY w.bookLanguage.id")
    List<Object[]> countBooksPerOption();

    @Modifying
    @Query("UPDATE Writing w SET w.bookLanguage = null WHERE w.bookLanguage.id = :languageId")
    void detachFromBooks(@Param("languageId") Long languageId);
}
