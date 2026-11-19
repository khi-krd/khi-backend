package ak.dev.khi_backend.khi_app.repository.publishment.writing;

import ak.dev.khi_backend.khi_app.model.publishment.writing.ManagedOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;
import java.util.Optional;

/**
 * Common contract for the writing vocabulary tables (writing types, editions,
 * book languages). {@link ManagedOptionService} works against this interface;
 * the per-entity {@code countBooks}/{@code detachFromBooks} queries stay on the
 * concrete repositories because their JPQL field names differ.
 */
@NoRepositoryBean
public interface ManagedOptionRepository<T extends ManagedOption> extends JpaRepository<T, Long> {

    List<T> findAllByActiveTrueOrderByDisplayOrderAsc();

    List<T> findAllByOrderByDisplayOrderAsc();

    Optional<T> findBySlug(String slug);

    /** Book counts for the whole list in one query: rows of [optionId, count]. */
    List<Object[]> countBooksPerOption();

    /** Deleting an option detaches it from every writing — records survive. */
    void detachFromBooks(Long id);
}
