package ak.dev.khi_backend.khi_app.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * One-time, idempotent backfill of the {@code sort_order} column that drives
 * the manual drag-and-drop ordering on the public/admin list endpoints.
 *
 * <p>Runs at every boot after Hibernate's {@code ddl-auto: update} has added
 * the column. Only rows whose {@code sort_order IS NULL} are touched: each
 * one is ranked by the table's <em>previous</em> default list order and given
 * a rank starting at {@code max(existing sort_order) + 1}, so the listing
 * renders exactly as before — ranked rows keep their position, previously
 * unranked rows keep their implicit tail order.</p>
 *
 * <p>Per-entity ranking mirrors the ORDER BY each list endpoint used before
 * manual ordering existed (and still uses as the tiebreak):</p>
 *
 * <ul>
 *   <li>{@code news}              → date_published DESC, created_at DESC</li>
 *   <li>{@code projects}          → id DESC</li>
 *   <li>{@code writings}          → created_at DESC</li>
 *   <li>{@code videos}            → created_at DESC</li>
 *   <li>{@code sound_tracks}      → created_at DESC</li>
 *   <li>{@code image_collections} → publishment_date DESC, created_at DESC</li>
 *   <li>{@code services}          → published_at DESC, created_at DESC</li>
 * </ul>
 *
 * <p>Featured/homepage ordering is a separate mechanism (featuredOrder) and
 * is intentionally not touched.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EntitySortOrderBackfill implements ApplicationRunner {

    private record TableSpec(String table, String orderBy) {}

    private static final List<TableSpec> TABLES = List.of(
            new TableSpec("news",              "date_published DESC, created_at DESC"),
            new TableSpec("projects",          "id DESC"),
            new TableSpec("writings",          "created_at DESC"),
            new TableSpec("videos",            "created_at DESC"),
            new TableSpec("sound_tracks",      "created_at DESC"),
            new TableSpec("image_collections", "publishment_date DESC, created_at DESC"),
            new TableSpec("services",          "published_at DESC, created_at DESC")
    );

    private final JdbcTemplate jdbc;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (TableSpec spec : TABLES) {
            backfill(spec);
        }
    }

    private void backfill(TableSpec spec) {
        if (!columnExists(spec.table(), "sort_order")) return;

        Integer nullCount = jdbc.queryForObject(
                "SELECT count(*) FROM " + spec.table() + " WHERE sort_order IS NULL",
                Integer.class);
        if (nullCount == null || nullCount == 0) return;

        // Rank unranked rows by the table's legacy default order, continuing
        // after the highest sort_order already assigned. ROW_NUMBER() makes
        // every rank distinct even when the tiebreak ties.
        int updated = jdbc.update("""
                WITH bounds AS (
                    SELECT COALESCE(MAX(sort_order), -1) + 1 AS start
                    FROM %s
                ),
                ranked AS (
                    SELECT id,
                           (SELECT start FROM bounds)
                               + CAST(ROW_NUMBER() OVER (ORDER BY %s) AS int) - 1
                               AS new_order
                    FROM %s
                    WHERE sort_order IS NULL
                )
                UPDATE %s t
                SET sort_order = ranked.new_order
                FROM ranked
                WHERE t.id = ranked.id
                """.formatted(spec.table(), spec.orderBy(), spec.table(), spec.table()));

        log.info("Sort order backfill: {} → {} row(s) ranked by '{}'",
                spec.table(), updated, spec.orderBy());
    }

    private boolean columnExists(String table, String column) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = ?",
                Integer.class, table, column);
        return n != null && n > 0;
    }
}
