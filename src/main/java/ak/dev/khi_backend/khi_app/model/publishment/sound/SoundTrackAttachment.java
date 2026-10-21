package ak.dev.khi_backend.khi_app.model.publishment.sound;

import ak.dev.khi_backend.khi_app.enums.publishment.AttachmentType;
import jakarta.persistence.*;
import lombok.*;

/**
 * SoundTrackAttachment — a supplementary file attached to a SoundTrack.
 *
 * Intended for PDF booklets, promotional videos, lyric sheets,
 * or any other file that accompanies a sound track release.
 *
 * The order is managed by @OrderColumn("attachment_order") on the parent side.
 */
@Entity
@Table(
        name = "sound_track_attachments",
        indexes = {
                @Index(name = "idx_attachment_track", columnList = "sound_track_id"),
                @Index(name = "idx_attachment_type",  columnList = "attachment_type")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SoundTrackAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * URL pointing to the attachment file (S3, CDN, hosted server …).
     */
    @Column(name = "file_url", nullable = false, length = 1200)
    private String fileUrl;

    /**
     * Human-readable title / label for this attachment.
     * e.g. "Album Booklet", "Making Of Video", "Lyrics PDF".
     */
    @Column(name = "title", length = 300)
    private String title;

    /** Sorani title — the legacy `title` column mirrors the primary one. */
    @Column(name = "title_ckb", length = 300)
    private String titleCkb;

    /** Kurmanji title. */
    @Column(name = "title_kmr", length = 300)
    private String titleKmr;

    /**
     * Type of attachment: PDF, VIDEO, IMAGE, AUDIO, OTHER.
     * See AttachmentType enum.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "attachment_type", nullable = false, length = 20)
    private AttachmentType attachmentType;

    /**
     * File size in bytes. Set 0 when unknown.
     */
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /**
     * Optional MIME type string.
     * e.g. "application/pdf", "video/mp4".
     */
    @Column(name = "mime_type", length = 100)
    private String mimeType;

    /**
     * Position in the attachments list.
     * Managed automatically by @OrderColumn on SoundTrack.attachments.
     */
    @Column(name = "attachment_order")
    private Integer attachmentOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sound_track_id", nullable = false)
    private SoundTrack soundTrack;
}