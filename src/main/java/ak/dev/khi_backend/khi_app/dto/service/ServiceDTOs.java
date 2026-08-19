package ak.dev.khi_backend.khi_app.dto.service;

import lombok.*;

import java.util.List;
import java.io.Serializable;

/**
 * ServiceDTOs — Request / response DTOs for the Service module.
 *
 * Service-level text is a list of {@link ServiceContentRequest} each carrying
 * a languageCode and a Tiptap HTML {@code description}.  All visual media
 * (image, video, voice, document, or any other file) is embedded inline in
 * that description as {@code <img>}, {@code <video>}, {@code <audio>}, or
 * {@code <a href>} tags whose URLs already point at S3.  The
 * {@link ak.dev.khi_backend.khi_app.service.media.TiptapHtmlProcessor}
 * intercepts every save and hoists any inline base64 payloads to S3.
 *
 * Service no longer carries a separate cover, hero, gallery, or per-file
 * media metadata model.
 */
public class ServiceDTOs {

    // =========================================================================
    // SERVICE — REQUEST
    // =========================================================================

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ServiceRequest {
        /** Dynamic service type. Examples: "Training", "Event", "Program", "Workshop" */
        private String serviceType;
        /** Physical or virtual location. Null when not applicable. */
        private String location;
        /**
         * Explicit publish timestamp.
         * Format: "yyyy-MM-dd HH:mm:ss"   Null = draft / unpublished.
         */
        private String publishedAt;
        /** Explicit nav / scroll order for the public page — lower first. Null sorts last. */
        private Integer sortOrder;
        /** Layout hint: "MEDIA_HERO" | "FEATURE_GRID" | "DEFAULT". */
        private String layoutType;
        private String heroVideoUrl;
        private String heroPosterUrl;
        /** Optional slug for #anchor links. Slug-like and unique when provided. */
        private String navAnchorId;
        /** RECOMMENDED ordered gallery — each slot IMAGE or VIDEO. */
        private List<MediaItem> galleryMedia;
        /** Legacy gallery fallback (used only when galleryMedia is empty). */
        private List<String> featureImageUrls;
        /** Legacy gallery fallback (used only when galleryMedia is empty). */
        private List<String> thumbnailUrls;
        private List<Long> partnerIds;
        /**
         * Bilingual content list.
         * Each entry must have a languageCode ("CKB" | "KMR") and a title.
         */
        private List<ServiceContentRequest> contents;
    }

    // ─── Gallery Media Slot ───────────────────────────────────────────────────

    /**
     * One ordered gallery slot. Shared by request and response.
     * Each slot is independently an IMAGE or a VIDEO.
     */
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class MediaItem implements Serializable {
        private static final long serialVersionUID = 1L;
        /** "IMAGE" or "VIDEO". Optional on input — auto-detected from the URL when omitted. */
        private String type;
        /** Image URL, or video file URL. Required when a slot is present. */
        private String url;
        /** Poster/thumbnail frame — recommended for VIDEO slots. */
        private String posterUrl;
        /** Optional alt / accessibility text. */
        private String alt;
    }

    // ─── Bilingual Service Content ────────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ServiceContentRequest {
        /** "CKB" (Sorani) or "KMR" (Kurmanji). */
        private String languageCode;
        private String title;
        /**
         * Tiptap HTML description — all media (image / video / voice / file)
         * is embedded inline here and rewritten to S3 URLs on save.
         */
        private String description;
        /**
         * Short plain-text line for the homepage featured carousel.
         * Optional — blank falls back to a tag-stripped excerpt of description.
         * Plain text only: any HTML is stripped on save.
         */
        private String featureDescription;
    }

    // =========================================================================
    // SERVICE — RESPONSE
    // =========================================================================

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ServiceResponse implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long id;
        private String serviceType;
        private String location;
        private boolean active;
        private String publishedAt;
        private Integer sortOrder;
        private String layoutType;
        private String heroVideoUrl;
        private String heroPosterUrl;
        private String navAnchorId;
        private List<MediaItem> galleryMedia;
        private List<String> featureImageUrls;
        private List<String> thumbnailUrls;
        private List<Long> partnerIds;
        private List<ServiceContentResponse> contents;
        /**
         * Featured state — read-only here. Write it through
         * {@code PATCH /api/v1/services/{id}/featured} so the global slide cap
         * stays enforced in one place.
         */
        private boolean featured;
        private Integer featuredOrder;
        private String featureImageUrl;
        private String createdAt;
        private String updatedAt;
    }

    // ─── Bilingual Service Content ────────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ServiceContentResponse implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long id;
        private String languageCode;
        private String title;
        /** Tiptap HTML description. */
        private String description;
        /** Short plain-text line used by the homepage featured carousel. */
        private String featureDescription;
    }
}
