package ak.dev.khi_backend.khi_app.model.publishment.video;

import jakarta.persistence.*;
import lombok.*;

/**
 * VideoClipItem — One individual clip inside a VIDEO_CLIP video.
 *
 * When a Video has videoType = VIDEO_CLIP, it represents a collection
 * (set) of short video clips. Each clip is stored as a VideoClipItem.
 *
 * URL strategy (same pattern used across the project):
 *   url         → direct S3 / CDN hosted file   (optional)
 *   externalUrl → YouTube watch, Vimeo page …   (optional)
 *   embedUrl    → YouTube embed, iframe URL …   (optional)
 * At least one of the three must be provided.
 */
@Entity
@Table(
        name = "video_clip_items",
        indexes = {
                @Index(name = "idx_clip_video_id",   columnList = "video_id"),
                @Index(name = "idx_clip_clip_number", columnList = "clip_number")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class VideoClipItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ─── Parent Video ──────────────────────────────────────────────────────────
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_id", nullable = false)
    private Video video;

    // ─── Source URLs ───────────────────────────────────────────────────────────

    /** Direct hosted file URL (S3 / CDN). */
    @Column(name = "url", columnDefinition = "TEXT")
    private String url;

    /** External watch page (e.g. YouTube watch URL). */
    @Column(name = "external_url", columnDefinition = "TEXT")
    private String externalUrl;

    /** Embeddable iframe URL (e.g. YouTube embed). */
    @Column(name = "embed_url", columnDefinition = "TEXT")
    private String embedUrl;

    // ─── Clip Metadata ─────────────────────────────────────────────────────────

    /** Order / sequence number within the parent clip collection. */
    @Column(name = "clip_number")
    private Integer clipNumber;

    /** Duration of this clip in seconds. */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /** Resolution, e.g. "1080p", "720p". */
    @Column(name = "resolution", length = 20)
    private String resolution;

    /** File format, e.g. "mp4", "webm". */
    @Column(name = "file_format", length = 20)
    private String fileFormat;

    /** File size in MB. */
    @Column(name = "file_size_mb")
    private Double fileSizeMb;

    // ─── Bilingual Clip Title ──────────────────────────────────────────────────

    @Column(name = "title_ckb", length = 300)
    private String titleCkb;

    @Column(name = "title_kmr", length = 300)
    private String titleKmr;

    // ─── Default Clip ─────────────────────────────────────────────────────────

    /**
     * True for the clip the detail page plays first. At most one clip per
     * video is main — see VideoService#normalizeClipMainFlag. Older rows
     * (pre-flag) carry all-false and the site falls back to clip order.
     */
    @Column(name = "is_main", nullable = false)
    private boolean main = false;

    // ─── Bilingual Clip Description ────────────────────────────────────────────

    @Column(name = "description_ckb", columnDefinition = "TEXT")
    private String descriptionCkb;

    @Column(name = "description_kmr", columnDefinition = "TEXT")
    private String descriptionKmr;
}
