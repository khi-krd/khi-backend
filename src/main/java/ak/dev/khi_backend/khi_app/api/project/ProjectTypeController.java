package ak.dev.khi_backend.khi_app.api.project;

import ak.dev.khi_backend.khi_app.dto.ApiResponse;
import ak.dev.khi_backend.khi_app.dto.project.ProjectTypeDtos.ProjectTypeRequest;
import ak.dev.khi_backend.khi_app.dto.project.ProjectTypeDtos.ProjectTypeResponse;
import ak.dev.khi_backend.khi_app.service.project.ProjectTypeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Project-type vocabulary — the options offered by the dashboard type
 * picker. Public reads, employee+ writes. Renames are propagated onto the
 * free-text type columns of matching projects; deletes only remove the
 * suggestion.
 */
@RestController
@RequestMapping("/api/v1/projects/types")
@RequiredArgsConstructor
@Tag(name = "Project Types", description = "Project-type CRUD; public reads, admin writes")
public class ProjectTypeController {

    private final ProjectTypeService projectTypeService;

    @GetMapping
    public ApiResponse<List<ProjectTypeResponse>> getTypes() {
        return ApiResponse.success(projectTypeService.list(), "Project types fetched");
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProjectTypeResponse> createType(@RequestBody(required = false) ProjectTypeRequest request) {
        return ApiResponse.success(projectTypeService.create(request), "Project type created");
    }

    @PutMapping("/{id}")
    public ApiResponse<ProjectTypeResponse> updateType(
            @PathVariable Long id, @RequestBody(required = false) ProjectTypeRequest request) {
        return ApiResponse.success(projectTypeService.update(id, request), "Project type updated");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteType(@PathVariable Long id) {
        projectTypeService.delete(id);
        return ApiResponse.success(null, "Project type deleted");
    }
}
