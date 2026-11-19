package ak.dev.khi_backend.khi_app.service.publishment.writing;

import ak.dev.khi_backend.khi_app.dto.publishment.writing.WritingDtos.ManagedOptionRequest;
import ak.dev.khi_backend.khi_app.dto.publishment.writing.WritingDtos.ManagedOptionResponse;
import ak.dev.khi_backend.khi_app.model.publishment.writing.ManagedOption;
import ak.dev.khi_backend.khi_app.repository.publishment.writing.ManagedOptionRepository;
import jakarta.persistence.EntityNotFoundException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * CRUD for the three editor-managed writing vocabularies (writing types,
 * editions, book languages) — a slimmed copy of {@link BookGenreService}
 * for single-select fields. Each concrete subclass (@Service) supplies its
 * repository, a row factory and the slug prefix used when a request's slug
 * cannot be normalised.
 */
public abstract class ManagedOptionService<T extends ManagedOption> {

    private static final Pattern SLUG_PATTERN = Pattern.compile("[A-Z0-9_]+");

    protected abstract ManagedOptionRepository<T> repository();
    protected abstract T newRow();
    /** Slug prefix for auto-generated rows, e.g. {@code "TYPE"} → TYPE_XXXX. */
    protected abstract String kind();

    /**
     * @param includeInactive dashboard passes {@code true} to see hidden rows;
     *                        the website leaves it {@code false}.
     */
    public List<ManagedOptionResponse> list(boolean includeInactive) {
        List<T> rows = includeInactive
                ? repository().findAllByOrderByDisplayOrderAsc()
                : repository().findAllByActiveTrueOrderByDisplayOrderAsc();
        Map<Long, Long> counts = repository().countBooksPerOption().stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
        return rows.stream()
                .map(row -> response(row, counts.getOrDefault(row.getId(), 0L)))
                .toList();
    }

    public ManagedOptionResponse create(ManagedOptionRequest request) {
        T row = newRow();
        apply(row, request);
        // saveAndFlush so a duplicate slug surfaces here as a 409, not at commit.
        return response(repository().saveAndFlush(row), 0L);
    }

    public ManagedOptionResponse update(Long id, ManagedOptionRequest request) {
        T row = repository().findById(id).orElseThrow(() -> notFound(id));
        long bookCount = repository().countBooksPerOption().stream()
                .filter(r -> id.equals(r[0]))
                .map(r -> (Long) r[1])
                .findFirst().orElse(0L);
        apply(row, request);
        return response(repository().saveAndFlush(row), bookCount);
    }

    /**
     * Detaches the option from every writing (the records survive untouched),
     * then deletes the row. Hiding an option without touching books is
     * {@code active: false} — always the recommendation.
     */
    public void delete(Long id) {
        if (!repository().existsById(id)) throw notFound(id);
        repository().detachFromBooks(id);
        repository().deleteById(id);
    }

    /** Resolves a writing request's option id, or throws for an unknown id. */
    public T resolve(Long id) {
        return repository().findById(id).orElseThrow(() ->
                new IllegalArgumentException("Unknown " + kind() + " id: " + id));
    }

    /** The seeded default row (BOOK / FIRST_EDITION), when it exists. */
    public T resolveDefault(String slug) {
        return repository().findBySlug(slug).orElse(null);
    }

    private void apply(T row, ManagedOptionRequest request) {
        String nameCkb = trimToNull(request != null ? request.getNameCkb() : null);
        String nameKmr = trimToNull(request != null ? request.getNameKmr() : null);
        // A chip with no name at all cannot be drawn — keep the existing name
        // on update; fall back to a placeholder on create.
        if (nameCkb == null && nameKmr == null) {
            nameCkb = row.getNameCkb();
            nameKmr = row.getNameKmr();
            if (nameCkb == null && nameKmr == null) nameCkb = "-";
        }
        row.setSlug(normalizeSlug(request != null ? request.getSlug() : null));
        row.setNameCkb(nameCkb);
        row.setNameKmr(nameKmr);
        row.setDisplayOrder(request == null || request.getDisplayOrder() == null ? 0 : request.getDisplayOrder());
        row.setActive(request == null || request.getActive() == null || request.getActive());
    }

    private String normalizeSlug(String slug) {
        String normalized = slug == null ? "" : slug.trim().toUpperCase(Locale.ROOT);
        if (!SLUG_PATTERN.matcher(normalized).matches()) {
            normalized = kind() + "_" + Long.toString(System.currentTimeMillis(), 36).toUpperCase(Locale.ROOT);
        }
        return normalized;
    }

    private ManagedOptionResponse response(T row, long bookCount) {
        return ManagedOptionResponse.builder()
                .id(row.getId())
                .slug(row.getSlug())
                .nameCkb(row.getNameCkb())
                .nameKmr(row.getNameKmr())
                .displayOrder(row.getDisplayOrder())
                .active(row.isActive())
                .bookCount(bookCount)
                .build();
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private EntityNotFoundException notFound(Long id) {
        return new EntityNotFoundException(kind() + " not found: " + id);
    }
}
