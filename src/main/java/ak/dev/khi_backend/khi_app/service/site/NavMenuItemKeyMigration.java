package ak.dev.khi_backend.khi_app.service.site;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One-time, idempotent itemKey correction. {@code itemKey} joins a row to a section
 * in the website's static nav config, which spells its sections {@code sound} and
 * {@code video} — but the seeded rows were written as {@code audio} and {@code
 * videos}, so their background photos never matched a section and silently did
 * nothing.
 *
 * <p>Runs at every boot after Hibernate's {@code ddl-auto: update}: rows already on
 * the canonical keys are left alone, and a rename is skipped when the target key is
 * already taken (the existing row wins — both can never hold photos at once, so the
 * {@code uk_nav_item_key} constraint stays intact).</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NavMenuItemKeyMigration implements ApplicationRunner {

    /** legacy itemKey -> the section key the website's nav config actually uses. */
    private static final Map<String, String> ALIASES = Map.of(
            "audio", "sound",
            "videos", "video");

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT item_key FROM nav_menu_items", String.class);
        Set<String> existing = new HashSet<>();
        for (String key : rows) {
            if (key != null) existing.add(key.trim().toLowerCase());
        }

        for (Map.Entry<String, String> alias : ALIASES.entrySet()) {
            String legacy = alias.getKey();
            String canonical = alias.getValue();
            if (!existing.contains(legacy)) continue;
            if (existing.contains(canonical)) {
                log.warn("Nav menu: both '{}' and '{}' rows exist — keeping '{}', "
                        + "the legacy row stays unmatched", legacy, canonical, canonical);
                continue;
            }
            int renamed = jdbcTemplate.update(
                    "UPDATE nav_menu_items SET item_key = ? WHERE lower(item_key) = ?",
                    canonical, legacy);
            if (renamed > 0) {
                log.info("Nav menu: renamed itemKey '{}' -> '{}' on {} row(s)",
                        legacy, canonical, renamed);
            }
        }
    }
}
