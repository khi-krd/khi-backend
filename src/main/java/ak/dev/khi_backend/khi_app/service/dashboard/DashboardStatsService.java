package ak.dev.khi_backend.khi_app.service.dashboard;

import ak.dev.khi_backend.khi_app.dto.dashboard.DashboardStatsDtos.BreakdownItem;
import ak.dev.khi_backend.khi_app.dto.dashboard.DashboardStatsDtos.DashboardStatsResponse;
import ak.dev.khi_backend.khi_app.dto.dashboard.DashboardStatsDtos.Dimension;
import ak.dev.khi_backend.khi_app.dto.dashboard.DashboardStatsDtos.ModuleStats;
import ak.dev.khi_backend.khi_app.dto.dashboard.DashboardStatsDtos.MonthPoint;
import ak.dev.khi_backend.khi_app.dto.dashboard.DashboardStatsDtos.OtherEntityStats;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only aggregation for the admin dashboard statistics page.
 *
 * Everything runs as set-based SQL against Postgres — counts, GROUP BYs and
 * month buckets are computed inside the database, never by loading rows into
 * the JVM. The six content modules share one monthly-bucket helper so the
 * per-module sparkline and the platform trend always line up.
 */
@Service
@RequiredArgsConstructor
public class DashboardStatsService {

    private static final int MONTH_WINDOW = 6;

    private final EntityManager em;

    @Transactional(readOnly = true)
    public DashboardStatsResponse stats() {
        List<String> months = lastMonths(MONTH_WINDOW);

        ModuleStats news = module("news", "news", months, List.of(
                dimension("category", categoryBreakdown()),
                dimension("featured", flagBreakdown("news", "featured"))
        ));
        ModuleStats projects = module("projects", "projects", months, List.of(
                dimension("status", enumBreakdown("projects", "status")),
                dimension("type", projectTypeBreakdown())
        ));
        ModuleStats videos = module("videos", "videos", months, List.of(
                dimension("type", enumBreakdown("videos", "video_type"))
        ));
        ModuleStats sounds = module("sounds", "sound_tracks", months, List.of(
                dimension("state", enumBreakdown("sound_tracks", "track_state")),
                dimension("soundType", stringBreakdown(
                        "SELECT sound_type, COUNT(*) FROM sound_track_sound_types GROUP BY sound_type"))
        ));
        ModuleStats writings = module("writings", "writings", months, List.of(
                dimension("kind", writingKindBreakdown())
        ));
        ModuleStats collections = module("collections", "image_collections", months, List.of(
                dimension("type", enumBreakdown("image_collections", "collection_type"))
        ));

        List<ModuleStats> modules = List.of(news, projects, videos, sounds, writings, collections);

        List<OtherEntityStats> others = otherEntities();

        long grandTotal = modules.stream().mapToLong(ModuleStats::total).sum()
                + others.stream().mapToLong(OtherEntityStats::total).sum();

        List<MonthPoint> trend = new ArrayList<>();
        for (int i = 0; i < months.size(); i++) {
            long sum = 0;
            for (ModuleStats m : modules) sum += m.monthly().get(i);
            trend.add(new MonthPoint(months.get(i), sum));
        }

        return new DashboardStatsResponse(
                Instant.now(), grandTotal, modules, others, trend);
    }

    // ─── Module assembly ──────────────────────────────────────────────────────

    private ModuleStats module(String key, String table, List<String> months, List<Dimension> dims) {
        long total = scalar("SELECT COUNT(*) FROM " + table);
        long[] buckets = monthlyBuckets(table, months);
        long thisYear = scalar(
                "SELECT COUNT(*) FROM " + table + " WHERE created_at >= ?",
                Timestamp.valueOf(LocalDate.now().withDayOfYear(1).atStartOfDay()));
        List<Long> monthly = new ArrayList<>(MONTH_WINDOW);
        for (long b : buckets) monthly.add(b);
        // The current month is always the last bucket of the window.
        return new ModuleStats(key, total, buckets[MONTH_WINDOW - 1], thisYear, monthly, dims);
    }

    private List<String> lastMonths(int count) {
        List<String> months = new ArrayList<>(count);
        YearMonth ym = YearMonth.now();
        for (int i = count - 1; i >= 0; i--) months.add(ym.minusMonths(i).toString());
        return months;
    }

    /** Per-month record counts for {@code table}, aligned to {@code months}. */
    private long[] monthlyBuckets(String table, List<String> months) {
        Map<String, Long> byMonth = new LinkedHashMap<>();
        for (Object[] row : rows(
                "SELECT to_char(date_trunc('month', created_at), 'YYYY-MM') AS m, COUNT(*) "
                        + "FROM " + table + " WHERE created_at IS NOT NULL GROUP BY 1")) {
            byMonth.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        long[] buckets = new long[months.size()];
        for (int i = 0; i < months.size(); i++) buckets[i] = byMonth.getOrDefault(months.get(i), 0L);
        return buckets;
    }

    // ─── Dimension builders ───────────────────────────────────────────────────

    private Dimension dimension(String key, List<BreakdownItem> items) {
        return new Dimension(key, items);
    }

    /** GROUP BY on an enum-typed column; {@code key} is the stored enum name. */
    private List<BreakdownItem> enumBreakdown(String table, String column) {
        return stringBreakdown("SELECT " + column + ", COUNT(*) FROM " + table + " GROUP BY " + column);
    }

    private List<BreakdownItem> stringBreakdown(String sql) {
        List<BreakdownItem> items = new ArrayList<>();
        for (Object[] row : rows(sql)) {
            String key = row[0] == null ? "none" : String.valueOf(row[0]);
            items.add(new BreakdownItem(key, null, null, ((Number) row[1]).longValue()));
        }
        items.sort((a, b) -> Long.compare(b.count(), a.count()));
        return items;
    }

    /** News split by category, keeping uncategorized rows under a "none" key. */
    private List<BreakdownItem> categoryBreakdown() {
        List<BreakdownItem> items = new ArrayList<>();
        for (Object[] row : rows(
                "SELECT nc.id, nc.name_ckb, nc.name_kmr, COUNT(*) "
                        + "FROM news n LEFT JOIN news_categories nc ON nc.id = n.category_id "
                        + "GROUP BY nc.id, nc.name_ckb, nc.name_kmr")) {
            if (row[0] == null) {
                items.add(new BreakdownItem("none", null, null, ((Number) row[3]).longValue()));
            } else {
                items.add(new BreakdownItem(
                        "cat-" + row[0],
                        row[1] == null ? null : String.valueOf(row[1]),
                        row[2] == null ? null : String.valueOf(row[2]),
                        ((Number) row[3]).longValue()));
            }
        }
        items.sort((a, b) -> Long.compare(b.count(), a.count()));
        return items;
    }

    private List<BreakdownItem> flagBreakdown(String table, String column) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : rows("SELECT " + column + ", COUNT(*) FROM " + table + " GROUP BY " + column)) {
            counts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        long trueCount = counts.getOrDefault("true", 0L);
        long falseCount = counts.getOrDefault("false", 0L);
        return List.of(
                new BreakdownItem("true", null, null, trueCount),
                new BreakdownItem("false", null, null, falseCount));
    }

    /**
     * Project types: managed selection rows first, then projects that only
     * carry the legacy primary-type columns (no selection rows) so every
     * project lands in exactly one bucket.
     */
    private List<BreakdownItem> projectTypeBreakdown() {
        Map<String, String[]> names = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();

        for (Object[] row : rows(
                "SELECT type_ckb, type_kmr, COUNT(*) FROM project_type_selections "
                        + "GROUP BY type_ckb, type_kmr")) {
            String ckb = row[0] == null ? "" : String.valueOf(row[0]);
            String kmr = row[1] == null ? "" : String.valueOf(row[1]);
            String key = ckb + "" + kmr;
            names.putIfAbsent(key, new String[]{ckb, kmr});
            counts.merge(key, ((Number) row[2]).longValue(), Long::sum);
        }
        for (Object[] row : rows(
                "SELECT project_type_ckb, project_type_kmr, COUNT(*) FROM projects p "
                        + "WHERE p.project_type_ckb IS NOT NULL AND p.project_type_ckb <> '' "
                        + "AND NOT EXISTS (SELECT 1 FROM project_type_selections s WHERE s.project_id = p.id) "
                        + "GROUP BY project_type_ckb, project_type_kmr")) {
            String ckb = String.valueOf(row[0]);
            String kmr = row[1] == null ? "" : String.valueOf(row[1]);
            String key = ckb + "" + kmr;
            names.putIfAbsent(key, new String[]{ckb, kmr});
            counts.merge(key, ((Number) row[2]).longValue(), Long::sum);
        }

        List<BreakdownItem> items = new ArrayList<>();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            String[] n = names.get(e.getKey());
            items.add(new BreakdownItem(e.getKey(), n[0].isEmpty() ? null : n[0],
                    n[1].isEmpty() ? null : n[1], e.getValue()));
        }
        items.sort((a, b) -> Long.compare(b.count(), a.count()));
        return items;
    }

    /** Writings: standalone books, books inside a series, and distinct series. */
    private List<BreakdownItem> writingKindBreakdown() {
        Object[] row = rows(
                "SELECT COUNT(*) FILTER (WHERE series_id IS NULL), "
                        + "COUNT(*) FILTER (WHERE series_id IS NOT NULL), "
                        + "COUNT(DISTINCT series_id) FROM writings").get(0);
        return List.of(
                new BreakdownItem("single", null, null, ((Number) row[0]).longValue()),
                new BreakdownItem("seriesMember", null, null, ((Number) row[1]).longValue()),
                new BreakdownItem("series", null, null, ((Number) row[2]).longValue()));
    }

    // ─── Other entities ───────────────────────────────────────────────────────

    private List<OtherEntityStats> otherEntities() {
        List<OtherEntityStats> out = new ArrayList<>();

        // Content modules' supporting taxonomies.
        out.add(other("topics", "publishment_topics", null,
                List.of(dimension("entityType", stringBreakdown(
                        "SELECT entity_type, COUNT(*) FROM publishment_topics GROUP BY entity_type")))));
        out.add(other("newsCategories", "news_categories", null, null));
        out.add(other("newsSubCategories", "news_sub_categories", null, null));
        out.add(other("bookGenres", "book_genres", "active", null));
        out.add(other("soundTrackTypes", "sound_track_types", null, null));
        out.add(other("projectTypes", "project_types", null, null));
        out.add(other("officeTypes", "office_types", null, null));

        // Site configuration records.
        out.add(other("aboutPages", "about_pages", "active", null));
        out.add(other("contactPages", "contact_pages", "active", null));
        out.add(other("services", "services", "active", null));
        out.add(other("featuredItems", "featured_items", "active", null));
        out.add(other("partners", "partners", "active", null));
        out.add(other("teamMembers", "team_members", "active", null));
        out.add(other("navMenuItems", "nav_menu_items", "active", null));
        out.add(other("socialLinks", "social_links", "active", null));
        out.add(other("staticTexts", "static_texts", null, null));
        out.add(other("siteFonts", "site_fonts", null, null));
        out.add(other("locations", "locations", null, null));
        out.add(other("siteSettings", "site_settings", null, null));
        out.add(other("donationTypeCards", "donation_type_cards", "active", null));
        out.add(other("donationArchiveVideos", "donation_archive_videos", "active", null));

        // Submissions and donations.
        out.add(other("contactMessages", "contact_messages", null,
                List.of(dimension("status", stringBreakdown(
                        "SELECT status, COUNT(*) FROM contact_messages GROUP BY status")))));
        out.add(other("financialDonations", "financial_donations", null,
                List.of(dimension("status", stringBreakdown(
                        "SELECT status, COUNT(*) FROM financial_donations GROUP BY status")))));
        out.add(other("archiveDonations", "archive_donations", null,
                List.of(dimension("status", stringBreakdown(
                        "SELECT status, COUNT(*) FROM archive_donations GROUP BY status")))));

        // Advertising clips attached to sounds/films.
        out.add(other("soundReklamVideos", "sound_reklam_videos", null, null));
        out.add(other("filmReklamVideos", "film_reklam_videos", null, null));

        // Dashboard users.
        out.add(other("users", "users_tbl", null, null));

        return out;
    }

    private OtherEntityStats other(String key, String table, String activeColumn, List<Dimension> dims) {
        long total = scalar("SELECT COUNT(*) FROM " + table);
        Long active = activeColumn == null
                ? null
                : scalar("SELECT COUNT(*) FROM " + table + " WHERE " + activeColumn);
        return new OtherEntityStats(key, total, active, dims);
    }

    // ─── Query helpers ────────────────────────────────────────────────────────

    private long scalar(String sql, Object... params) {
        var query = em.createNativeQuery(sql);
        for (int i = 0; i < params.length; i++) query.setParameter(i + 1, params[i]);
        Object result = query.getSingleResult();
        return result == null ? 0L : ((Number) result).longValue();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(String sql) {
        return em.createNativeQuery(sql).getResultList();
    }
}
