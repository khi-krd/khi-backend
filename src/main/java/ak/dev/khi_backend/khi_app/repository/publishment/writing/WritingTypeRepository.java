package ak.dev.khi_backend.khi_app.repository.publishment.writing;

import ak.dev.khi_backend.khi_app.model.publishment.writing.WritingType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WritingTypeRepository extends ManagedOptionRepository<WritingType> {

    /** How many writings point at this type — the delete warning. */
    @Query("SELECT COUNT(w) FROM Writing w WHERE w.writingType.id = :typeId")
    long countBooks(@Param("typeId") Long typeId);

    /** Book counts for the whole list in one query: rows of [typeId, count]. */
    @Query("SELECT w.writingType.id, COUNT(w) FROM Writing w WHERE w.writingType IS NOT NULL GROUP BY w.writingType.id")
    List<Object[]> countBooksPerOption();

    /**
     * Deleting a type detaches it from every writing — the records survive
     * untouched, they simply show no type until a new one is picked.
     */
    @Modifying
    @Query("UPDATE Writing w SET w.writingType = null WHERE w.writingType.id = :typeId")
    void detachFromBooks(@Param("typeId") Long typeId);
}
