package ak.dev.khi_backend.khi_app.model.publishment.sound;

import jakarta.persistence.*;
import lombok.*;

/**
 * SoundTrackBrochure — a single brochure image attached to a SoundTrackFile.
 *
 * Each SoundTrackFile can have an ordered list of brochure images
 * (e.g. front cover, back cover, inner booklet pages).
 *
 * The order is managed by @OrderColumn("brochure_order") on the parent side.
 */
@Entity
@Table(
        name = "sound_track_brochures",
        indexes = {
                @Index(name = "idx_brochure_file", columnList = "sound_track_file_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SoundTrackBrochure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * URL of the brochure image.
     * Can point to S3, CDN, or any hosted image URL.
     */
    @Column(name = "image_url", nullable = false, length = 1200)
    private String imageUrl;

    /**
     * Optional caption / label for this brochure image.
     * e.g. "Front Cover", "Page 2", "Inner Booklet".
     */
    @Column(name = "caption", length = 300)
    private String caption;

    /** Sorani caption — the legacy `caption` column mirrors the primary one. */
    @Column(name = "caption_ckb", length = 300)
    private String captionCkb;

    /** Kurmanji caption. */
    @Column(name = "caption_kmr", length = 300)
    private String captionKmr;

    /**
     * Position in the brochure list.
     * Managed automatically by @OrderColumn on SoundTrackFile.brochures.
     * You don't need to set this manually.
     */
    @Column(name = "brochure_order")
    private Integer brochureOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sound_track_file_id", nullable = false)
    private SoundTrackFile soundTrackFile;
}
