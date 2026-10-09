package ak.dev.khi_backend.khi_app.dto.contact;

import lombok.*;

/**
 * ContactDTOs — Request / response DTOs for the Contact module.
 *
 * Contact no longer carries any standalone hero or gallery field.  All
 * visual media (image, video, voice, document, or any other file) lives
 * inside the bilingual Tiptap {@code description} HTML on each language's
 * content embeddable.
 */
public class ContactDTOs {

    // =========================================================================
    // CONTACT PAGE — REQUEST
    // =========================================================================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ContactRequest {

        /** Sorani (CKB) URL slug — required, unique. */
        private String slugCkb;

        /** Kurmanji (KMR) URL slug — optional, unique. */
        private String slugKmr;

        /** Sorani (CKB) page-level content (title / subtitle / address / hours / Tiptap description). */
        private ContactContentRequest ckbContent;

        /** Kurmanji (KMR) page-level content (title / subtitle / address / hours / Tiptap description). */
        private ContactContentRequest kmrContent;

        // ─── Contact Details ─────────────────────────────────────────────────

        private String phone;
        private String secondaryPhone;
        private String email;
        private String mapEmbedUrl;
        private Double latitude;
        private Double longitude;
        private String heroImageUrl;
        private String officeType;
        private String badgeCkb;
        private String badgeKmr;
        private Boolean active;
        private Integer displayOrder;
    }

    // ─── Bilingual page-level text ────────────────────────────────────────────

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ContactContentRequest {
        private String title;
        private String subtitle;
        private String address;
        private String workingHours;
        /**
         * Tiptap HTML description — all media (image / video / voice / file)
         * is embedded inline here and rewritten to S3 URLs on save.
         */
        private String description;
    }

    // =========================================================================
    // CONTACT PAGE — RESPONSE
    // =========================================================================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ContactResponse {
        private Long   id;
        private String slugCkb;
        private String slugKmr;

        private ContactContentResponse ckbContent;
        private ContactContentResponse kmrContent;

        private String phone;
        private String secondaryPhone;
        private String email;
        private String mapEmbedUrl;
        private Double latitude;
        private Double longitude;
        private String heroImageUrl;
        private String officeType;
        private String badgeCkb;
        private String badgeKmr;
        private Integer displayOrder;

        private boolean active;
        private String  createdAt;
        private String  updatedAt;
    }

    // ─── Bilingual page-level text ────────────────────────────────────────────

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ContactContentResponse {
        private String title;
        private String subtitle;
        private String address;
        private String workingHours;
        /** Tiptap HTML description. */
        private String description;
    }
}
