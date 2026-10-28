package ak.dev.khi_backend.khi_app.service.publishment.sound;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * One-time, idempotent move of the sound fields that used to be shared by both
 * languages into their independent Sorani / Kurmanji homes:
 *
 * <ul>
 *   <li>{@code sound_track_locations} → {@code sound_track_locations_ckb|_kmr}</li>
 *   <li>{@code sound_track_files.form|genre|recording_venue} → {@code *_ckb|*_kmr}</li>
 * </ul>
 *
 * <p>A shared value carries no language tag, so each one is routed by script:
 * Arabic-script text is Sorani, anything else Kurmanji. Moved values are
 * removed from the legacy table / columns, so every later boot finds nothing
 * to do. Runs after Hibernate's {@code ddl-auto: update} has created the new
 * tables and columns.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SoundLanguageFieldsMigration implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    /** True when the text contains Arabic-script letters (Sorani). */
    public static boolean isSorani(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '\u0600' && c <= '\u06FF') || (c >= '\u0750' && c <= '\u077F')
                    || (c >= '\uFB50' && c <= '\uFDFF') || (c >= '\uFE70' && c <= '\uFEFF')) {
                return true;
            }
        }
        return false;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        migrateLocations();
        migrateFileColumns();
    }

    private boolean tableExists(String table) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = ?",
                Integer.class, table);
        return n != null && n > 0;
    }

    private boolean columnExists(String table, String column) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                Integer.class, table, column);
        return n != null && n > 0;
    }

    private void migrateLocations() {
        if (!tableExists("sound_track_locations")) return;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT sound_track_id, location FROM sound_track_locations");
        if (rows.isEmpty()) return;

        int ckb = 0, kmr = 0;
        for (Map<String, Object> row : rows) {
            Object trackId = row.get("sound_track_id");
            String location = (String) row.get("location");
            if (location == null || location.isBlank()) continue;
            boolean sorani = isSorani(location);
            String table = sorani ? "sound_track_locations_ckb" : "sound_track_locations_kmr";
            String column = sorani ? "location_ckb" : "location_kmr";
            jdbc.update("INSERT INTO " + table + " (sound_track_id, " + column + ") "
                            + "SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM " + table
                            + " WHERE sound_track_id = ? AND " + column + " = ?)",
                    trackId, location, trackId, location);
            if (sorani) ckb++; else kmr++;
        }
        jdbc.update("DELETE FROM sound_track_locations");
        log.info("Sound locations: moved {} Sorani + {} Kurmanji value(s) into per-language lists",
                ckb, kmr);
    }

    private void migrateFileColumns() {
        if (!tableExists("sound_track_files")) return;
        int moved = 0;
        for (String legacy : List.of("form", "genre", "recording_venue")) {
            if (!columnExists("sound_track_files", legacy)) continue;
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, " + legacy + " AS v FROM sound_track_files WHERE " + legacy + " IS NOT NULL");
            for (Map<String, Object> row : rows) {
                String value = (String) row.get("v");
                if (value != null && !value.isBlank()) {
                    String target = legacy + (isSorani(value) ? "_ckb" : "_kmr");
                    // Never overwrite a value an editor already set per language.
                    jdbc.update("UPDATE sound_track_files SET " + target + " = COALESCE("
                            + target + ", ?) WHERE id = ?", value, row.get("id"));
                    moved++;
                }
                jdbc.update("UPDATE sound_track_files SET " + legacy + " = NULL WHERE id = ?",
                        row.get("id"));
            }
        }
        if (moved > 0) {
            log.info("Sound files: moved {} shared form/genre/venue value(s) into per-language columns",
                    moved);
        }
    }
}
