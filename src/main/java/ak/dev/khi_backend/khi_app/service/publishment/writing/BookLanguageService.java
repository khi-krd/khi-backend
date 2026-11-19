package ak.dev.khi_backend.khi_app.service.publishment.writing;

import ak.dev.khi_backend.khi_app.model.publishment.writing.BookLanguage;
import ak.dev.khi_backend.khi_app.repository.publishment.writing.BookLanguageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BookLanguageService extends ManagedOptionService<BookLanguage> {

    private final BookLanguageRepository repository;

    @Override protected BookLanguageRepository repository() { return repository; }
    @Override protected BookLanguage newRow() { return new BookLanguage(); }
    @Override protected String kind() { return "LANGUAGE"; }
}
