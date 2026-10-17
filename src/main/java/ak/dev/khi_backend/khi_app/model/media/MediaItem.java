package ak.dev.khi_backend.khi_app.model.media;

import ak.dev.khi_backend.khi_app.enums.MediaKind;
import lombok.*;

import java.io.Serializable;

/**
 * MediaItem — a single asset inside a JSONB-backed gallery on the entities
 * that still maintain a standalone gallery (News, Project).
 *
 * Stored as one element of a JSONB column ({@code media_gallery}) so the
 * order, count, and shape are preserved without a join table.  Plain POJO —
 * no JPA annotations — mirroring {@link ak.dev.khi_backend.khi_app.model.about.StatItem}.
 *
 * Files are uploaded via {@code POST /api/v1/media/upload}; the returned
 * {@code fileUrl} is set on {@link #url} and the {@link #kind} discriminates
 * how the frontend renders it.
 *
 * About, Contact, and Service do NOT use this type — they embed all media
 * inline in their Tiptap description HTML instead.
 *
 * Field meanings:
 *   url           — S3 / CDN URL of the asset (required unless embedUrl/externalUrl set).
 *   kind          — IMAGE | VIDEO | AUDIO; drives the player on the frontend.
 *   thumbnailUrl  — optional poster (VIDEO) or cover art (AUDIO); ignored for IMAGE.
 *   embedUrl      — iframe/embed URL (SoundCloud player, YouTube embed, …);
 *                   rendered as an <iframe> instead of a native player.
 *   externalUrl   — external page link (SoundCloud track page, YouTube watch, …);
 *                   rendered as a link-out when nothing playable is available.
 *   captionCkb    — Sorani caption shown under the asset.
 *   captionKmr    — Kurmanji caption shown under the asset.
 *   sortOrder     — display order inside the gallery (ascending).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MediaItem implements Serializable {

    private String url;

    private MediaKind kind;

    private String thumbnailUrl;

    private String embedUrl;

    private String externalUrl;

    private String captionCkb;

    private String captionKmr;

    private Integer sortOrder;
}
