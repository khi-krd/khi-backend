package ak.dev.khi_backend.khi_app.service.publishment.writing;

import ak.dev.khi_backend.khi_app.model.publishment.writing.Edition;
import ak.dev.khi_backend.khi_app.repository.publishment.writing.EditionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EditionService extends ManagedOptionService<Edition> {

    /** Slug of the seeded default — new books fall back to چاپی یەکەم. */
    public static final String DEFAULT_SLUG = "FIRST_EDITION";

    private final EditionRepository repository;

    @Override protected EditionRepository repository() { return repository; }
    @Override protected Edition newRow() { return new Edition(); }
    @Override protected String kind() { return "EDITION"; }
}
