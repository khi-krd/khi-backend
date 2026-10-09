package ak.dev.khi_backend.khi_app.api.site;

import ak.dev.khi_backend.khi_app.dto.ApiResponse;
import ak.dev.khi_backend.khi_app.service.site.StaticTextService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Editable static text blocks.
 *  - GET  /api/v1/text-blocks?locale=ckb  → public {key: value} map for the site
 *  - GET  /api/v1/text-blocks/all         → admin listing (all locales per key)
 *  - PUT  /api/v1/text-blocks             → admin bulk upsert {entries:[...]}
 *  - DELETE /api/v1/text-blocks/{id}      → admin remove
 */
@RestController
@RequestMapping("/api/v1/text-blocks")
@RequiredArgsConstructor
@Tag(name = "Static Text Blocks", description = "Admin-editable UI text")
public class StaticTextController {

    private final StaticTextService service;

    /** Public: all texts for one locale as a flat {key: value} map. */
    @GetMapping
    @Operation(summary = "Public text map for a locale")
    public ResponseEntity<Map<String, String>> publicMap(
            @RequestParam(defaultValue = "ckb") String locale) {
        return ResponseEntity.ok(service.getPublicMap(locale));
    }

    /** Admin: every key with its per-locale values. */
    @GetMapping("/all")
    @Operation(summary = "Admin: list all text blocks")
    public ResponseEntity<List<Map<String, Object>>> listAll() {
        return ResponseEntity.ok(service.listAll());
    }

    /** Admin: bulk upsert. Body: {entries:[{key, locale, value}]}. */
    @PutMapping
    @Operation(summary = "Admin: upsert text blocks")
    public ResponseEntity<ApiResponse<Map<String, Object>>> upsert(
            @RequestBody(required = false) UpsertRequest body) {
        List<StaticTextService.Entry> entries = body == null ? null : body.entries();
        int saved = service.upsert(entries);
        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .success(true)
                .message("Saved " + saved + " text block(s)")
                .data(Map.of("saved", saved))
                .build());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Admin: delete a text block")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    public record UpsertRequest(List<StaticTextService.Entry> entries) {}
}
