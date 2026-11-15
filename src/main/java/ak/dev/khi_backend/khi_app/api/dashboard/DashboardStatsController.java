package ak.dev.khi_backend.khi_app.api.dashboard;

import ak.dev.khi_backend.khi_app.dto.ApiResponse;
import ak.dev.khi_backend.khi_app.dto.dashboard.DashboardStatsDtos.DashboardStatsResponse;
import ak.dev.khi_backend.khi_app.service.dashboard.DashboardStatsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin dashboard statistics — aggregated record counts across every entity.
 * Admin-only via SecurityConfig (the generic {@code GET /api/v1/**} public
 * read rule would otherwise expose it).
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "Admin dashboard statistics")
public class DashboardStatsController {

    private final DashboardStatsService dashboardStatsService;

    @GetMapping("/stats")
    public ApiResponse<DashboardStatsResponse> stats() {
        return ApiResponse.success(dashboardStatsService.stats(), "Dashboard statistics fetched");
    }
}
