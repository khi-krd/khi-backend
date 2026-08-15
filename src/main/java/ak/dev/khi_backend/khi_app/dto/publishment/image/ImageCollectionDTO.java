package ak.dev.khi_backend.khi_app.dto.publishment.image;

import ak.dev.khi_backend.khi_app.enums.Language;
import ak.dev.khi_backend.khi_app.enums.publishment.ImageCollectionType;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public final class ImageCollectionDTO {

    private ImageCollectionDTO() {}

    // =========================================================================
    // SHARED: Language content (embedded bilingual fields)
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class LanguageContentDto {
        private String title;
        private String description;
        private String topic;       // free-text topic name inside the content block
        private String location;
        private String collectedBy;
    }

    // =========================================================================
    // SHARED: Bilingual tag/keyword set
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class BilingualSet {
        private Set<String> ckb;
        private Set<String> kmr;
    }

    // =========================================================================
    // SHARED: Inline topic creation
    // =========================================================================

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class InlineTopicRequest {
        private String nameCkb;
        private String nameKmr;
    }

    // =========================================================================
    // SHARED: Single image item inside the album
    // =========================================================================

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class ImageItemDto {

        /**
         * Existing album item ID. On update, this keeps the persisted item and
         * its current source when no replacement file or URL is supplied.
         */
        private Long id;

        /** Direct S3 / CDN URL — used when no upload file is attached. */
        private String imageUrl;

        /** External page link (Flickr, Unsplash, etc.) */
        private String externalUrl;

        /** Embed / iframe URL */
        private String embedUrl;

        private String captionCkb;
        private String captionKmr;
        private String descriptionCkb;
        private String descriptionKmr;

        /** Display order inside the collection (0-based). */
        private Integer sortOrder;

        // ─── AUTO-EXTRACTED METADATA FIELDS (Response only) ───────────────────

        /** File size in bytes - auto detected during upload */
        private Long fileSizeBytes;

        /** Image width in pixels - auto detected during upload */
        private Integer widthPx;

        /** Image height in pixels - auto detected during upload */
        private Integer heightPx;

        /** MIME type (image/jpeg, image/png, etc.) - auto detected */
        private String mimeType;

        /** Aspect ratio (width/height) - calculated from dimensions */
        private Double aspectRatio;

        /** Human readable file size (e.g., "2.4 MB", "850 KB") */
        private String humanReadableSize;
    }

    // =========================================================================
    // CREATE REQUEST
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class CreateRequest {

        private String slugCkb;
        private String slugKmr;

        @NotNull(message = "collectionType is required")
        private ImageCollectionType collectionType;

        // ── Cover URLs (fallback when no multipart file is uploaded) ──────────
        private String ckbCoverUrl;
        private String kmrCoverUrl;
        private String hoverCoverUrl;

        // ── Topic ─────────────────────────────────────────────────────────────
        private Long topicId;
        private InlineTopicRequest newTopic;

        // ── Core fields ───────────────────────────────────────────────────────
        private LocalDate publishmentDate;

        @NotNull(message = "At least one content language is required")
        private Set<Language> contentLanguages;

        private LanguageContentDto ckbContent;
        private LanguageContentDto kmrContent;

        private BilingualSet tags;
        private BilingualSet keywords;

        /** Album items supplied as JSON (URL-only sources). */
        private List<ImageItemDto> imageAlbum;
    }

    // =========================================================================
    // UPDATE REQUEST
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class UpdateRequest {

        private String slugCkb;
        private String slugKmr;

        private ImageCollectionType collectionType;

        // ── Cover URLs ─────────────────────────────────────────────────────────
        private String ckbCoverUrl;
        private String kmrCoverUrl;
        private String hoverCoverUrl;

        // ── Topic ─────────────────────────────────────────────────────────────
        private Long topicId;
        private InlineTopicRequest newTopic;
        private boolean clearTopic;

        // ── Core fields ───────────────────────────────────────────────────────
        private LocalDate publishmentDate;
        private Set<Language> contentLanguages;

        private LanguageContentDto ckbContent;
        private LanguageContentDto kmrContent;

        private BilingualSet tags;
        private BilingualSet keywords;

        /** null = keep existing, non-null = replace entirely */
        private List<ImageItemDto> imageAlbum;
    }

    // =========================================================================
    // RESPONSE
    // =========================================================================

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class Response {

        private Long id;
        private String slugCkb;
        private String slugKmr;
        private ImageCollectionType collectionType;

        // ── Three cover image URLs ────────────────────────────────────────────
        private String ckbCoverUrl;
        private String kmrCoverUrl;
        private String hoverCoverUrl;
        /** Hero picture for the homepage carousel; written via the featured PATCH. */
        private String featureImageUrl;

        // ── Topic ─────────────────────────────────────────────────────────────
        private Long   topicId;
        private String topicNameCkb;
        private String topicNameKmr;

        // ── Core ──────────────────────────────────────────────────────────────
        private LocalDate     publishmentDate;
        private Set<Language> contentLanguages;

        private LanguageContentDto ckbContent;
        private LanguageContentDto kmrContent;

        private BilingualSet tags;
        private BilingualSet keywords;

        /** Now includes metadata: widthPx, heightPx, fileSizeBytes, mimeType, etc. */
        private List<ImageItemDto> imageAlbum;

        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }
}
