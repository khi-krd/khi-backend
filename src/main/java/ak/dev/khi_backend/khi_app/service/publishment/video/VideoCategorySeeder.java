package ak.dev.khi_backend.khi_app.service.publishment.video;

import ak.dev.khi_backend.khi_app.model.publishment.video.VideoCategory;
import ak.dev.khi_backend.khi_app.repository.publishment.video.VideoCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Seeds the initial video categories — same contract as
 * {@code WritingOptionsSeeder}: runs once at boot, only fills a completely
 * empty table, and never overwrites rows an admin has since managed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VideoCategorySeeder implements ApplicationRunner {

    private final VideoCategoryRepository repository;

    private record Seed(String slug, String nameCkb, String nameKmr, int order) {}

    private static final List<Seed> CATEGORIES = List.of(
            new Seed("MOVIE",                "فیلم",                 "Fîlm",                0),
            new Seed("CLIP",                 "کلیپ",                 "Klîp",                1),
            new Seed("FOLKLORE_PROGRAM",     "بەرنامەی فۆلکلۆری",     "Bernameya folklorê",  2),
            new Seed("CULTURAL_PROGRAM",     "بەرنامەی کولتووری",     "Bernameya çandî",     3),
            new Seed("INTERVIEW",            "چاوپێکەوتن",           "Hevpeyvîn",            4),
            new Seed("DOCUMENTARY",          "بەڵگەفیلم",            "Belgefîlm",            5),
            new Seed("EDUCATIONAL_PROGRAM",  "بەرنامەی پەروەردەیی",   "Bernameya perwerdê",  6),
            new Seed("HISTORICAL_PROGRAM",   "بەرنامەی مێژوویی",      "Bernameya dîrokî",    7));

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) return; // admin-managed table — never overwrite
        List<VideoCategory> rows = CATEGORIES.stream().map(s -> {
            VideoCategory c = new VideoCategory();
            c.setSlug(s.slug());
            c.setNameCkb(s.nameCkb());
            c.setNameKmr(s.nameKmr());
            c.setDisplayOrder(s.order());
            c.setActive(true);
            return c;
        }).toList();
        repository.saveAll(rows);
        log.info("Seeded {} video categories row(s)", rows.size());
    }
}
