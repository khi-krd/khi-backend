package ak.dev.khi_backend.khi_app.model.publishment.video;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

/**
 * VideoSourceFile — one video source belonging to a FILM-type {@link Video}.
 *
 * ─── Why this exists ──────────────────────────────────────────────────────────
 *  A FILM used to carry a single {@code sourceUrl}. When an admin uploaded
 *  several video files for one film, only the first was saved and the rest were
 *  silently dropped. A FILM now keeps an ORDERED list of these sources so every
 *  uploaded file is preserved, and exactly one is flagged {@link #main}.
 *
 *  The {@code main} source is mirrored back onto the video's legacy
 *  {@code sourceUrl} / {@code sourceExternalUrl} / {@code sourceEmbedUrl}
 *  columns so existing consumers keep working unchanged.
 *
 *  Each source may be a hosted file ({@link #url}), an external page link
 *  ({@link #externalUrl}), or an embeddable iframe URL ({@link #embedUrl}).
 *  Only relevant for FILM; VIDEO_CLIP keeps its sources on each clip item.
 */
@Embeddable
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoSourceFile {

    /** Direct hosted file URL (S3 / CDN). */
    @Column(name = "url", columnDefinition = "TEXT")
    private String url;

    /** External page link (YouTube watch, Vimeo …). */
    @Column(name = "external_url", columnDefinition = "TEXT")
    private String externalUrl;

    /** Embeddable iframe URL. */
    @Column(name = "embed_url", columnDefinition = "TEXT")
    private String embedUrl;

    /** True for the primary source. Exactly one source per film is main. */
    @Builder.Default
    @Column(name = "is_main", nullable = false)
    private boolean main = false;

    /** Optional friendly label, e.g. "Part 1", "1080p", "Trailer". */
    @Column(name = "label", length = 300)
    private String label;

    /** Sorani label — the legacy `label` column mirrors the primary one. */
    @Column(name = "label_ckb", length = 300)
    private String labelCkb;

    /** Kurmanji label. */
    @Column(name = "label_kmr", length = 300)
    private String labelKmr;

    /** Optional per-source runtime in seconds. */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;
}
