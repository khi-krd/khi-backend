package ak.dev.khi_backend.khi_app.dto.site;

import lombok.*;

/**
 * DTOs for the website hamburger menu (`/api/v1/nav-menu`).
 *
 * <p>The menu is a fixed set of sections keyed by {@code itemKey} — the website owns
 * the labels, hrefs and secondary links from its static config. The only thing the
 * CMS manages is the full-screen background photo behind each section, so the write
 * contract is just the image (plus {@code active} to switch a photo off without
 * clearing it).</p>
 */
public final class NavMenuDtos {

    private NavMenuDtos() {}

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class NavMenuItemRequest {
        /** {@code null}/blank clears the background photo. */
        private String imageUrl;
        /** {@code null} leaves the flag unchanged. */
        private Boolean active;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class NavMenuItemResponse {
        private Long id;
        private String itemKey;
        private String labelCkb;
        private String labelKmr;
        private String href;
        private String imageUrl;
        private Integer displayOrder;
        private Boolean active;
    }
}
