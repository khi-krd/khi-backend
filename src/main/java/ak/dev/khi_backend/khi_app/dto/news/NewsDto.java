package ak.dev.khi_backend.khi_app.dto.news;

import ak.dev.khi_backend.khi_app.enums.Language;
import ak.dev.khi_backend.khi_app.enums.MediaKind;
import ak.dev.khi_backend.khi_app.model.media.MediaItem;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * NewsDto — request / response DTO for the News module (Tiptap migration).
 *
 * Inline media (images, audio, video, embedded HTML) lives inside the Tiptap
 * HTML stored in {@link LanguageContentDto#getDescription()}. The old
 * {@code media[]} array and the {@code news_media} table are gone.
 *
 * Cover image flow: client uploads via {@code POST /api/v1/media/upload}
 * first, then sends the resulting {@code coverUrl} in this DTO's JSON body.
 */
@Data
@NoArgsConstructor @AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class NewsDto {

    private Long id;
    private String coverUrl;
    /** Type of {@link #coverUrl} — IMAGE | VIDEO | AUDIO. Defaults to IMAGE. */
    private MediaKind coverMediaType;
    /** Optional poster (VIDEO) or cover art (AUDIO) URL for the cover. */
    private String coverThumbnailUrl;
    /**
     * Hero picture used by the homepage carousel. Read-only here — it is written
     * through {@code PATCH /api/v1/news/{id}/featured}, so saving an article never
     * disturbs it.
     */
    private String featureImageUrl;
    /** Mixed-type gallery rendered beside the cover — images, videos, audios. */
    @Builder.Default
    private List<MediaItem> mediaGallery = new ArrayList<>();
    private LocalDate datePublished;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Builder.Default
    private Set<Language> contentLanguages = new LinkedHashSet<>();

    private CategoryDto category;

    private SubCategoryDto subCategory;

    private LanguageContentDto ckbContent;
    private LanguageContentDto kmrContent;

    private BilingualSet tags;

    private BilingualSet keywords;

    // ============================================================
    // NESTED DTOs
    // ============================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class CategoryDto {
        private String ckbName;
        private String kmrName;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class SubCategoryDto {
        private String ckbName;
        private String kmrName;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class LanguageContentDto {
        private String title;
        /** Tiptap HTML produced by the editor. */
        private String description;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class BilingualSet {
        @Builder.Default
        private Set<String> ckb = new LinkedHashSet<>();
        @Builder.Default
        private Set<String> kmr = new LinkedHashSet<>();
    }
}
