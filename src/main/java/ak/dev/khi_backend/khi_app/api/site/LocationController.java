package ak.dev.khi_backend.khi_app.api.site;

import ak.dev.khi_backend.khi_app.dto.ApiResponse;
import ak.dev.khi_backend.khi_app.dto.site.LocationDtos.LocationRequest;
import ak.dev.khi_backend.khi_app.dto.site.LocationDtos.LocationResponse;
import ak.dev.khi_backend.khi_app.service.site.LocationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Location vocabulary — the options of the dashboard location picker shared
 * by every content form. Public reads, employee+ create/update, admin delete.
 */
@RestController
@RequestMapping("/api/v1/locations")
@RequiredArgsConstructor
@Tag(name = "Locations", description = "Managed location options; public reads, dashboard CRUD")
public class LocationController {

    private final LocationService locationService;

    @GetMapping
    public ApiResponse<List<LocationResponse>> getLocations() {
        return ApiResponse.success(locationService.list(), "Locations fetched");
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LocationResponse> createLocation(
            @RequestBody(required = false) LocationRequest request) {
        return ApiResponse.success(locationService.create(request), "Location created");
    }

    @PutMapping("/{id}")
    public ApiResponse<LocationResponse> updateLocation(
            @PathVariable Long id,
            @RequestBody(required = false) LocationRequest request) {
        return ApiResponse.success(locationService.update(id, request), "Location updated");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteLocation(@PathVariable Long id) {
        locationService.delete(id);
        return ApiResponse.success(null, "Location deleted");
    }
}
