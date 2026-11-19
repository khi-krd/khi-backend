package ak.dev.khi_backend.khi_app.service.publishment.writing;

import ak.dev.khi_backend.khi_app.model.publishment.writing.BookLanguage;
import ak.dev.khi_backend.khi_app.model.publishment.writing.Edition;
import ak.dev.khi_backend.khi_app.model.publishment.writing.ManagedOption;
import ak.dev.khi_backend.khi_app.model.publishment.writing.WritingType;
import ak.dev.khi_backend.khi_app.repository.publishment.writing.BookLanguageRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.writing.EditionRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.writing.WritingTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Seeds the initial vocabulary rows for the writings module — same pattern as
 * {@code BookGenreSeeder}/{@code NavMenuItemSeeder}: runs once at boot, only
 * fills a table that is completely empty, and never touches rows an admin has
 * since edited or added.
 *
 *   writing_types   → کتێب, بەڵگەنامە, توێژینەوە, وتار, نامە, ڕاپۆرت
 *   editions        → چاپی یەکەم (the auto-selected default for new books)
 *   book_languages  → the six Kurdish varieties from the spec
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WritingOptionsSeeder implements ApplicationRunner {

    private final WritingTypeRepository  writingTypeRepository;
    private final EditionRepository      editionRepository;
    private final BookLanguageRepository bookLanguageRepository;

    private record Seed(String slug, String nameCkb, String nameKmr, int order) {}

    private static final List<Seed> WRITING_TYPES = List.of(
            new Seed("BOOK",           "کتێب",      "Pirtûk",      0),
            new Seed("DOCUMENT",       "بەڵگەنامە",   "Belge",        1),
            new Seed("RESEARCH_PAPER", "توێژینەوە",  "Lêkolîn",      2),
            new Seed("ARTICLE",        "وتار",      "Gotar",        3),
            new Seed("LETTER",         "نامە",       "Name",         4),
            new Seed("REPORT",         "ڕاپۆرت",     "Raport",       5));

    private static final List<Seed> EDITIONS = List.of(
            new Seed("FIRST_EDITION", "چاپی یەکەم", "Çapa yekem", 0));

    private static final List<Seed> BOOK_LANGUAGES = List.of(
            new Seed("KURDISH_UPPER",   "کوردی سەروو",    "Kurdî jorîn", 0),
            new Seed("KURDISH_LOWER",   "کوردی خواروو",   "Kurdî jêrîn", 1),
            new Seed("KURDISH_CENTRAL", "کوردی ناوەڕاست", "Kurdî navîn", 2),
            new Seed("KURDI_JORIN",     "Kurdî jorîn",    "کوردی سەروو",  3),
            new Seed("KURDI_JERIN",     "Kurdî jêrîn",    "کوردی خواروو", 4),
            new Seed("KURDI_NAVIN",     "Kurdî navîn",    "کوردی ناوەڕاست", 5));

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed("writing types", WRITING_TYPES, writingTypeRepository::count,
                s -> { WritingType t = new WritingType(); fill(t, s); return t; },
                writingTypeRepository::saveAll);
        seed("editions", EDITIONS, editionRepository::count,
                s -> { Edition e = new Edition(); fill(e, s); return e; },
                editionRepository::saveAll);
        seed("book languages", BOOK_LANGUAGES, bookLanguageRepository::count,
                s -> { BookLanguage l = new BookLanguage(); fill(l, s); return l; },
                bookLanguageRepository::saveAll);
    }

    private void fill(ManagedOption row, Seed s) {
        row.setSlug(s.slug());
        row.setNameCkb(s.nameCkb());
        row.setNameKmr(s.nameKmr());
        row.setDisplayOrder(s.order());
        row.setActive(true);
    }

    private <T> void seed(String label, List<Seed> seeds, Supplier<Long> count,
                          java.util.function.Function<Seed, T> factory,
                          Consumer<List<T>> saveAll) {
        if (count.get() > 0) return; // admin-managed table — never overwrite
        saveAll.accept(seeds.stream().map(factory).toList());
        log.info("Seeded {} {} row(s)", seeds.size(), label);
    }
}
