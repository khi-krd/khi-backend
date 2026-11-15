package ak.dev.khi_backend.khi_app.dto.dashboard;

import java.time.Instant;
import java.util.List;

/**
 * Aggregated content statistics for the admin dashboard home.
 *
 * Every number is a real database count — the service never fabricates or
 * estimates. {@code modules} covers the six publishment/content entities with
 * their type/status dimensions; {@code otherEntities} is a flat list of every
 * remaining record-bearing table; {@code monthlyTrend} sums new records per
 * month across the six content modules for the last six months.
 */
public final class DashboardStatsDtos {

    private DashboardStatsDtos() {}

    public record DashboardStatsResponse(
            Instant generatedAt,
            long grandTotal,
            List<ModuleStats> modules,
            List<OtherEntityStats> otherEntities,
            List<MonthPoint> monthlyTrend
    ) {}

    /** One content entity (news, projects, videos, sounds, writings, collections). */
    public record ModuleStats(
            String key,
            long total,
            long createdThisMonth,
            long createdThisYear,
            /** Counts per month for the last six months, aligned with monthlyTrend. */
            List<Long> monthly,
            List<Dimension> dimensions
    ) {}

    /** A named breakdown axis, e.g. "status", "type", "category". */
    public record Dimension(String key, List<BreakdownItem> items) {}

    /**
     * One slice of a dimension. {@code key} is a stable machine key (enum name
     * or synthetic id) the frontend maps to a label; {@code nameCkb}/{@code nameKmr}
     * carry DB-driven display names (categories, project types) when they exist.
     */
    public record BreakdownItem(String key, String nameCkb, String nameKmr, long count) {}

    /** Any other record-bearing table. {@code active} is set only when the table has an active flag. */
    public record OtherEntityStats(
            String key,
            long total,
            Long active,
            List<Dimension> dimensions
    ) {}

    /** {@code month} is "YYYY-MM". */
    public record MonthPoint(String month, long total) {}
}
