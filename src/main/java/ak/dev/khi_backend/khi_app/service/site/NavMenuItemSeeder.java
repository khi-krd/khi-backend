package ak.dev.khi_backend.khi_app.service.site;

import ak.dev.khi_backend.khi_app.model.site.NavMenuItem;
import ak.dev.khi_backend.khi_app.repository.site.NavMenuItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Idempotent seed of the fixed nav-menu rows. Each website nav section expects
 * a CMS row carrying its background photo; the rows have no create/delete API
 * (PUT is image-only), so if the table is emptied — manual cleanup, a restored
 * dump, anything — the dashboard's menu editor silently renders empty.
 *
 * <p>Runs at every boot after Hibernate's {@code ddl-auto: update}: only the
 * canonical keys that are MISSING are inserted, so existing rows — and their
 * uploaded photos — are never touched.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NavMenuItemSeeder implements ApplicationRunner {

    private final NavMenuItemRepository repository;

    private record SectionSeed(String itemKey, String labelCkb, String labelKmr, String href) {}

    /** One row per website nav section, in the site's own order. */
    private static final List<SectionSeed> SECTIONS = List.of(
            new SectionSeed("news",     "هەواڵ",       "Nûçe",       "/news"),
            new SectionSeed("projects", "پڕۆژە",       "Proje",      "/projects"),
            new SectionSeed("sound",    "دەنگ",        "Deng",       "/audio"),
            new SectionSeed("video",    "دەنگ و ڕەنگ",  "Deng û Reng", "/videos"),
            new SectionSeed("gallery",  "وێنە",        "Wêne",       "/gallery"),
            new SectionSeed("writings", "نووسین",      "Nivîs",      "/writings"),
            new SectionSeed("services", "خزمەتگوزاری",  "Xizmet",     "/services"),
            new SectionSeed("about",    "ئێمە",        "Em",         "/about"),
            new SectionSeed("contact",  "پەیوەندی",     "Têkilî",     "/contact"),
            new SectionSeed("donate",   "بەخشین",      "Bexşîn",     "/donate"));

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Set<String> existing = repository.findAll().stream()
                .map(NavMenuItem::getItemKey)
                .filter(k -> k != null)
                .map(k -> k.trim().toLowerCase())
                .collect(Collectors.toSet());

        List<NavMenuItem> toInsert = new ArrayList<>();
        int order = 1;
        for (SectionSeed seed : SECTIONS) {
            if (!existing.contains(seed.itemKey())) {
                toInsert.add(NavMenuItem.builder()
                        .itemKey(seed.itemKey())
                        .labelCkb(seed.labelCkb())
                        .labelKmr(seed.labelKmr())
                        .href(seed.href())
                        .displayOrder(order)
                        .active(true)
                        .build());
            }
            order++;
        }

        if (toInsert.isEmpty()) {
            return;
        }

        repository.saveAll(toInsert);
        log.info("Nav menu: seeded {} missing section row(s): {}",
                toInsert.size(),
                toInsert.stream().map(NavMenuItem::getItemKey).collect(Collectors.joining(", ")));
    }
}
