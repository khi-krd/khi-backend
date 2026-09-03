package ak.dev.khi_backend.khi_app.service.publishment.writing;

import ak.dev.khi_backend.khi_app.model.publishment.writing.BookGenre;
import ak.dev.khi_backend.khi_app.repository.publishment.writing.BookGenreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * One-time, idempotent migration from the fixed BookGenre enum to editor-managed
 * rows. Runs at every boot after Hibernate ({@code ddl-auto: update}) has
 * created {@code book_genres} and {@code book_genre_links}:
 *
 * <ol>
 *   <li>Seeds the 22 genres with their bilingual names when the table is empty
 *       — display order matches the order readers see today.</li>
 *   <li>When {@code book_genre_links} is empty and the frozen pre-migration
 *       table {@code writing_book_genres} exists, converts every book's enum
 *       values into link rows. Legacy alias codes are mapped the same way the
 *       enum's JSON output always did (POLITICAL → POLITICS, ACADEMIC →
 *       EDUCATIONAL, ESSAY → OTHER); a truly unknown code gets its own new
 *       genre row rather than being dropped silently.</li>
 * </ol>
 *
 * The legacy {@code writing_book_genres} table itself is left untouched as a
 * rollback snapshot; drop it manually once the website's switch to dynamic
 * genres is confirmed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookGenreSeeder implements ApplicationRunner {

    /** slug, nameCkb, nameKmr — display order is the position in this list. */
    private static final String[][] SEED = {
            {"POETRY",      "شیعر",          "Şîir"},
            {"NOVEL",       "ڕۆمان",         "Roman"},
            {"SHORT_STORY", "چیرۆکی کورت",   "Çîroka kurt"},
            {"DRAMA",       "شانۆ",          "Şano"},
            {"HISTORY",     "مێژوو",         "Dîrok"},
            {"BIOGRAPHY",   "ژیاننامە",      "Jiyanname"},
            {"PHILOSOPHY",  "فەلسەفە",       "Felsefe"},
            {"RELIGION",    "ئایین",         "Ol"},
            {"FOLKLORE",    "زارگوتن",       "Zargotina gelêrî"},
            {"POLITICS",    "سیاسەت",        "Siyaset"},
            {"SOCIOLOGY",   "کۆمەڵناسی",     "Komelezanî"},
            {"ECONOMICS",   "ئابووری",       "Aborî"},
            {"LAW",         "یاسا",          "Qanûn"},
            {"LINGUISTICS", "زمانناسی",      "Zimanzanî"},
            {"ARTS",        "هونەر",         "Huner"},
            {"CULTURAL",    "کولتووری",      "Kultûrî"},
            {"SCIENCE",     "زانست",         "Zanist"},
            {"MEDICINE",    "پزیشکی",        "Pizîşkî"},
            {"EDUCATIONAL", "پەروەردەیی",    "Perwerdeyî"},
            {"CHILDREN",    "منداڵان",       "Zarokan"},
            {"TRAVEL",      "گەشتوگوزار",    "Ger û gerr"},
            {"OTHER",       "یتر",           "Yên din"},
    };

    private final BookGenreRepository bookGenreRepository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedGenres();
        backfillLinks();
    }

    private void seedGenres() {
        if (bookGenreRepository.count() > 0) return;
        for (int i = 0; i < SEED.length; i++) {
            bookGenreRepository.save(BookGenre.builder()
                    .slug(SEED[i][0])
                    .nameCkb(SEED[i][1])
                    .nameKmr(SEED[i][2])
                    .displayOrder(i)
                    .active(true)
                    .build());
        }
        log.info("Seeded {} book genres", SEED.length);
    }

    private void backfillLinks() {
        Integer legacyTable = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'writing_book_genres'",
                Integer.class);
        if (legacyTable == null || legacyTable == 0) return; // fresh database — nothing to migrate

        Integer links = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM book_genre_links", Integer.class);
        if (links != null && links > 0) return; // already migrated (or in live use)

        List<String> legacyValues = jdbcTemplate.queryForList(
                "SELECT DISTINCT book_genre FROM writing_book_genres", String.class);
        int inserted = 0;
        for (String legacy : legacyValues) {
            String slug = canonicalSlug(legacy);
            BookGenre genre = bookGenreRepository.findBySlug(slug).orElseGet(() -> {
                // A code outside the enum (hand-inserted row): keep it as its own
                // genre rather than dropping a book's genre silently. Editors can
                // rename it in the dashboard.
                log.warn("Unknown legacy book genre '{}' — creating its own row", slug);
                return bookGenreRepository.save(BookGenre.builder()
                        .slug(slug).nameCkb(slug).displayOrder(SEED.length).active(true)
                        .build());
            });
            // NOT EXISTS also dedupes alias collapse (e.g. a book holding both
            // POLITICAL and POLITICS becomes one link).
            inserted += jdbcTemplate.update("""
                    INSERT INTO book_genre_links (book_id, genre_id)
                    SELECT wbg.writing_id, ?
                    FROM writing_book_genres wbg
                    WHERE wbg.book_genre = ?
                      AND NOT EXISTS (SELECT 1 FROM book_genre_links l
                                      WHERE l.book_id = wbg.writing_id AND l.genre_id = ?)
                    """, genre.getId(), legacy, genre.getId());
        }
        if (inserted > 0) {
            log.info("Migrated {} book-genre links from writing_book_genres", inserted);
        }
    }

    /** Same normalisation the enum's JSON output has always applied. */
    private String canonicalSlug(String legacy) {
        String value = legacy == null ? "" : legacy.trim().toUpperCase();
        return switch (value) {
            case "POLITICAL" -> "POLITICS";
            case "ACADEMIC"  -> "EDUCATIONAL";
            case "ESSAY"     -> "OTHER";
            case "RELIGIOUS" -> "RELIGION";
            default          -> value;
        };
    }
}
