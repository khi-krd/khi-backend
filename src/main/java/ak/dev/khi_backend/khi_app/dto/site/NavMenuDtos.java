package ak.dev.khi_backend.khi_app.dto.site;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.List;

/**
 * DTOs for the website hamburger menu (`/api/v1/nav-menu`).
 *
 * <p>Secondary links have no CRUD of their own: the item request carries the whole
 * {@code links} array and the server replaces the set. {@code links == null} leaves
 * the existing links untouched, {@code links == []} removes them all.</p>
 */
public final class NavMenuDtos {

    private NavMenuDtos() {}

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class NavMenuLinkRequest {
        @NotBlank @Size(max = 200) private String labelCkb;
        @Size(max = 200) private String labelKmr;
        @NotBlank @Size(max = 300) private String href;
        private Integer displayOrder;
        private Boolean active;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class NavMenuItemRequest {
        /** Stable handle — do not change it after creation, the website keys off it. */
        @NotBlank @Size(max = 60) private String itemKey;
        @NotBlank @Size(max = 200) private String labelCkb;
        @Size(max = 200) private String labelKmr;
        private String descriptionCkb;
        private String descriptionKmr;
        @NotBlank @Size(max = 300) private String href;
        private String imageUrl;
        private Integer displayOrder;
        private Boolean active;
        /** Omitted -> links untouched. Empty list -> all links removed. */
        @Valid private List<NavMenuLinkRequest> links;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class NavMenuLinkResponse {
        private Long id;
        private String labelCkb;
        private String labelKmr;
        private String href;
        private Integer displayOrder;
        private Boolean active;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class NavMenuItemResponse {
        private Long id;
        private String itemKey;
        private String labelCkb;
        private String labelKmr;
        private String descriptionCkb;
        private String descriptionKmr;
        private String href;
        private String imageUrl;
        private Integer displayOrder;
        private Boolean active;
        private List<NavMenuLinkResponse> links;
    }
}
