package ak.dev.khi_backend.khi_app.service.publishment.writing;

import ak.dev.khi_backend.khi_app.model.publishment.writing.WritingType;
import ak.dev.khi_backend.khi_app.repository.publishment.writing.WritingTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WritingTypeService extends ManagedOptionService<WritingType> {

    /** Slug of the seeded default — new writings fall back to کتێب. */
    public static final String DEFAULT_SLUG = "BOOK";

    private final WritingTypeRepository repository;

    @Override protected WritingTypeRepository repository() { return repository; }
    @Override protected WritingType newRow() { return new WritingType(); }
    @Override protected String kind() { return "TYPE"; }
}
