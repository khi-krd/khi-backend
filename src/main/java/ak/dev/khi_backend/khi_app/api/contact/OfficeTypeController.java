package ak.dev.khi_backend.khi_app.api.contact;

import ak.dev.khi_backend.khi_app.dto.ApiResponse;
import ak.dev.khi_backend.khi_app.dto.contact.OfficeTypeDtos.OfficeTypeRequest;
import ak.dev.khi_backend.khi_app.dto.contact.OfficeTypeDtos.OfficeTypeResponse;
import ak.dev.khi_backend.khi_app.service.contact.OfficeTypeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Office-type vocabulary — the options offered by the dashboard office picker
 * on contact pages. Public reads, employee+ writes. Renames are propagated
 * onto the free-text {@code officeType} column of matching contact rows;
 * deletes only remove the suggestion.
 */
@RestController
@RequestMapping("/api/v1/contact/office-types")
@RequiredArgsConstructor
@Tag(name = "Office Types", description = "Office-type CRUD; public reads, admin writes")
public class OfficeTypeController {

    private final OfficeTypeService officeTypeService;

    @GetMapping
    public ApiResponse<List<OfficeTypeResponse>> getTypes() {
        return ApiResponse.success(officeTypeService.list(), "Office types fetched");
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OfficeTypeResponse> createType(
            @RequestBody(required = false) OfficeTypeRequest request) {
        return ApiResponse.success(officeTypeService.create(request), "Office type created");
    }

    @PutMapping("/{id}")
    public ApiResponse<OfficeTypeResponse> updateType(
            @PathVariable Long id,
            @RequestBody(required = false) OfficeTypeRequest request) {
        return ApiResponse.success(officeTypeService.update(id, request), "Office type updated");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteType(@PathVariable Long id) {
        officeTypeService.delete(id);
        return ApiResponse.success(null, "Office type deleted");
    }
}
