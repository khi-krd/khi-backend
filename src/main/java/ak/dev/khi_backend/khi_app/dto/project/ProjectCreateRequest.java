package ak.dev.khi_backend.khi_app.dto.project;

import ak.dev.khi_backend.khi_app.enums.Language;
import ak.dev.khi_backend.khi_app.enums.MediaKind;
import ak.dev.khi_backend.khi_app.enums.project.ProjectStatus;
import ak.dev.khi_backend.khi_app.model.media.MediaItem;
import ak.dev.khi_backend.khi_app.model.project.ProjectContentBlock;
import lombok.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * ProjectCreateRequest — Tiptap migration.
 *
 * The {@code media[]} array, the {@code contentsCkb / contentsKmr} string
 * lists, and the multipart cover upload have been dropped. Cover image and
 * any inline media are uploaded separately via
 * {@code POST /api/v1/media/upload}, and the returned URLs are sent here
 * inside {@code coverUrl} (top-level) and inside the Tiptap HTML in
 * {@code ckbContent.description} / {@code kmrContent.description}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectCreateRequest {

    private String coverUrl;

    /** Type of {@link #coverUrl} — IMAGE | VIDEO | AUDIO. Defaults to IMAGE. */
    private MediaKind coverMediaType;

    /** Optional poster (VIDEO) or cover art (AUDIO) URL for the card cover. */
    private String coverThumbnailUrl;

    /** Mixed-type gallery rendered beside the cover — images, videos, audios. */
    private List<MediaItem> mediaGallery;

    private String projectTypeCkb;

    private String projectTypeKmr;

    private ProjectStatus status;

    private Set<Language> contentLanguages;

    private LocalDate projectDate;

    /**
     * Sorani (CKB) content. The {@code description} field accepts Tiptap HTML.
     */
    private ProjectContentBlock ckbContent;

    /**
     * Kurmanji (KMR) content. The {@code description} field accepts Tiptap HTML.
     */
    private ProjectContentBlock kmrContent;

    private List<String> tagsCkb;
    private List<String> tagsKmr;

    private List<String> keywordsCkb;
    private List<String> keywordsKmr;
}
