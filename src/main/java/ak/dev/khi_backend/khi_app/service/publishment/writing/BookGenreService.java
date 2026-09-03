package ak.dev.khi_backend.khi_app.service.publishment.writing;

import ak.dev.khi_backend.khi_app.dto.publishment.writing.WritingDtos.BookGenreRequest;
import ak.dev.khi_backend.khi_app.dto.publishment.writing.WritingDtos.BookGenreResponse;
import ak.dev.khi_backend.khi_app.model.publishment.writing.BookGenre;
import ak.dev.khi_backend.khi_app.repository.publishment.writing.BookGenreRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * CRUD for editor-managed book genres, mirroring the social-links pattern, plus
 * the resolver {@link #resolve} that WritingService uses to turn a book
 * request's genre ids (or legacy enum codes) into genre rows.
 */
@Service
@RequiredArgsConstructor
public class BookGenreService {

    /** Stable machine key: UPPERCASE letters/digits/underscore, as in the old enum codes. */
    private static final Pattern SLUG_PATTERN = Pattern.compile("[A-Z0-9_]+");

    private final BookGenreRepository bookGenreRepository;

    /**
     * @param includeInactive dashboard passes {@code true} to see hidden rows;
     *                        the website leaves it {@code false}.
     */
    @Transactional(readOnly = true)
    public List<BookGenreResponse> getGenres(boolean includeInactive) {
        List<BookGenre> genres = includeInactive
                ? bookGenreRepository.findAllByOrderByDisplayOrderAsc()
                : bookGenreRepository.findAllByActiveTrueOrderByDisplayOrderAsc();
        Map<Long, Long> counts = bookGenreRepository.countBooksPerGenre().stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
        return genres.stream()
                .map(g -> response(g, counts.getOrDefault(g.getId(), 0L)))
                .toList();
    }

    @Transactional
    public BookGenreResponse createGenre(BookGenreRequest request) {
        BookGenre genre = new BookGenre();
        apply(genre, request);
        // saveAndFlush so a duplicate slug surfaces here as a 409, not at commit.
        return response(bookGenreRepository.saveAndFlush(genre), 0L);
    }

    @Transactional
    public BookGenreResponse updateGenre(Long id, BookGenreRequest request) {
        BookGenre genre = bookGenreRepository.findById(id)
                .orElseThrow(() -> notFound(id));
        long bookCount = bookGenreRepository.countBooks(id);
        String newSlug = normalizeSlug(request.getSlug());
        if (bookCount > 0 && !genre.getSlug().equals(newSlug)) {
            throw new IllegalArgumentException("Slug cannot change while books use this genre");
        }
        apply(genre, request);
        return response(bookGenreRepository.saveAndFlush(genre), bookCount);
    }

    /**
     * Detaches the genre from every book (the books survive untouched), then
     * deletes the row. Hiding a chip without touching books is
     * {@code active: false} — always the recommendation.
     */
    @Transactional
    public void deleteGenre(Long id) {
        if (!bookGenreRepository.existsById(id)) throw notFound(id);
        bookGenreRepository.detachFromBooks(id);
        bookGenreRepository.deleteById(id);
    }

    /**
     * Resolves a book request's genres to rows: {@code genreIds} wins when
     * non-empty; otherwise the legacy enum codes are normalised (POLITICAL →
     * POLITICS etc., via the enum's own JSON mapping) and matched against slug.
     * Returns an empty set when neither field has values — the caller decides
     * whether that is legal (create: no; update: leave unchanged).
     */
    @Transactional(readOnly = true)
    public Set<BookGenre> resolve(
            Collection<Long> genreIds,
            Collection<ak.dev.khi_backend.khi_app.enums.publishment.BookGenre> legacyGenres) {
        Set<BookGenre> resolved = new LinkedHashSet<>();
        if (genreIds != null && !genreIds.isEmpty()) {
            for (Long id : genreIds) {
                resolved.add(bookGenreRepository.findById(id).orElseThrow(() ->
                        new IllegalArgumentException("Unknown genre id: " + id)));
            }
            return resolved;
        }
        if (legacyGenres != null) {
            for (var legacy : legacyGenres) {
                if (legacy == null) continue;
                String slug = legacy.toJson();
                resolved.add(bookGenreRepository.findBySlug(slug).orElseThrow(() ->
                        new IllegalArgumentException("Unknown genre slug: " + slug)));
            }
        }
        return resolved;
    }

    private void apply(BookGenre genre, BookGenreRequest request) {
        String nameCkb = trimToNull(request.getNameCkb());
        String nameKmr = trimToNull(request.getNameKmr());
        // The website falls back to the other language when one is missing, but
        // a chip with no name at all cannot be drawn.
        if (nameCkb == null && nameKmr == null) {
            throw new IllegalArgumentException(
                    "At least one of nameCkb / nameKmr is required.");
        }
        genre.setSlug(normalizeSlug(request.getSlug()));
        genre.setNameCkb(nameCkb);
        genre.setNameKmr(nameKmr);
        genre.setDisplayOrder(request.getDisplayOrder() == null ? 0 : request.getDisplayOrder());
        genre.setActive(request.getActive() == null || request.getActive());
    }

    private String normalizeSlug(String slug) {
        String normalized = slug == null ? "" : slug.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty() || !SLUG_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "slug must be non-blank and contain only letters, digits and underscores: "
                            + slug);
        }
        return normalized;
    }

    private BookGenreResponse response(BookGenre genre, long bookCount) {
        return BookGenreResponse.builder()
                .id(genre.getId())
                .slug(genre.getSlug())
                .nameCkb(genre.getNameCkb())
                .nameKmr(genre.getNameKmr())
                .displayOrder(genre.getDisplayOrder())
                .active(genre.isActive())
                .bookCount(bookCount)
                .build();
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private EntityNotFoundException notFound(Long id) {
        return new EntityNotFoundException("Book genre not found: " + id);
    }
}
