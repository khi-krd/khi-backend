package ak.dev.khi_backend.khi_app.dto.project;

import ak.dev.khi_backend.khi_app.enums.Language;
import ak.dev.khi_backend.khi_app.enums.MediaKind;
import ak.dev.khi_backend.khi_app.enums.project.ProjectStatus;
import ak.dev.khi_backend.khi_app.model.media.MediaItem;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * ProjectResponse — Tiptap migration.
 *
 * The {@code description} inside each content block now contains Tiptap HTML
 * (with inline image / audio / video URLs already pointing at S3). The old
 * {@code media[]} array and {@code contentsCkb / contentsKmr} string lists
 * are gone.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectResponse {

    private Long id;
    private String coverUrl;
    private MediaKind coverMediaType;
    private String coverThumbnailUrl;
    /** Hero picture for the homepage carousel; written via the featured PATCH. */
    private String featureImageUrl;
    private List<MediaItem> mediaGallery;

    private String projectTypeCkb;
    private String projectTypeKmr;

    private ProjectStatus status;

    private LocalDate projectDate;
    private Set<Language> contentLanguages;

    private ProjectContentBlockDto ckbContent;
    private ProjectContentBlockDto kmrContent;

    private List<String> tagsCkb;
    private List<String> tagsKmr;

    private List<String> keywordsCkb;
    private List<String> keywordsKmr;

    private Instant createdAt;
    private Instant updatedAt;
    private String  createdBy;
    private String  updatedBy;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProjectContentBlockDto {
        private String title;
        /** Tiptap HTML output. */
        private String description;
        private String location;
    }
}
