package ak.dev.khi_backend.khi_app.model.publishment.sound;

import ak.dev.khi_backend.khi_app.enums.publishment.AttachmentType;
import jakarta.persistence.*;
import lombok.*;

/**
 * SoundAlbumBrochure — a brochure file attached to a SoundTrack as a whole
 * (album level), independent of the individual sound files.
 *
 * Intended for PDF booklets and scanned booklet pages / cover art
 * that accompany a sound release. Unlike {@link SoundTrackBrochure},
 * which holds image-only pages tied to a single {@link SoundTrackFile},
 * this collection accepts mixed file types (PDF, images, …).
 *
 * Ordered by {@code brochureOrder} via @OrderBy on the parent side.
 */
@Entity
@Table(
        name = "sound_album_brochures",
        indexes = {
                @Index(name = "idx_album_brochure_track", columnList = "sound_track_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SoundAlbumBrochure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * URL pointing to the brochure file (R2, CDN, hosted server …).
     */
    @Column(name = "file_url", nullable = false, length = 1200)
    private String fileUrl;

    /**
     * Optional human-readable title / caption for this brochure page or
     * document. e.g. "ڕووکاری پێشەوە", "کتێبی ئەلبووم".
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
     * Kind of brochure file: PDF, IMAGE, OTHER.
     * Shares the {@link AttachmentType} vocabulary.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false, length = 20)
    private AttachmentType fileType;

    /**
     * File size in bytes. Set 0 when unknown.
     */
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /**
     * Optional MIME type string.
     * e.g. "application/pdf", "image/jpeg".
     */
    @Column(name = "mime_type", length = 100)
    private String mimeType;

    /**
     * Position in the album brochures list.
     */
    @Column(name = "brochure_order")
    private Integer brochureOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sound_track_id", nullable = false)
    private SoundTrack soundTrack;
}
