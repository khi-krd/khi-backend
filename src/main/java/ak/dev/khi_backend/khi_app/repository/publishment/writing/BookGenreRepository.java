package ak.dev.khi_backend.khi_app.repository.publishment.writing;

import ak.dev.khi_backend.khi_app.model.publishment.writing.BookGenre;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BookGenreRepository extends JpaRepository<BookGenre, Long> {
    /** Website: only the genres an admin has switched on. */
    List<BookGenre> findAllByActiveTrueOrderByDisplayOrderAsc();

    /** Dashboard: hidden rows too, so they can be switched back on. */
    List<BookGenre> findAllByOrderByDisplayOrderAsc();

    /** Slugs are stored trimmed and upper-cased, so an exact match suffices. */
    Optional<BookGenre> findBySlug(String slug);

    /** How many books link to this genre — the slug-change and delete guard. */
    @Query("SELECT COUNT(w) FROM Writing w JOIN w.genres g WHERE g.id = :genreId")
    long countBooks(@Param("genreId") Long genreId);

    /** Book counts for the whole list in one query: rows of [genreId, count]. */
    @Query("SELECT g.id, COUNT(w) FROM Writing w JOIN w.genres g GROUP BY g.id")
    List<Object[]> countBooksPerGenre();

    /**
     * Deleting a genre detaches it from every book. Hibernate will not cascade
     * across the join table from this side, so the link rows go first.
     */
    @Modifying
    @Query(value = "DELETE FROM book_genre_links WHERE genre_id = :genreId", nativeQuery = true)
    void detachFromBooks(@Param("genreId") Long genreId);
}
