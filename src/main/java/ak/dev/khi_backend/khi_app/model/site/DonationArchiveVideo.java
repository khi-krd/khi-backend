package ak.dev.khi_backend.khi_app.model.site;

import jakarta.persistence.*;
import lombok.*;

/**
 * One clip in the donate page's archive-donors section — a short video of a
 * person handing over their archive material to the institute. The website
 * sorts by displayOrder and renders whatever active rows exist; an empty table
 * simply hides the section.
 *
 * `videoUrl` accepts either an uploaded file URL (R2) or a YouTube link — the
 * website's player resolves both, so no separate provider column is needed.
 */
@Entity
@Table(name = "donation_archive_videos")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DonationArchiveVideo {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "title_ckb", length = 200) private String titleCkb;
    @Column(name = "title_kmr", length = 200) private String titleKmr;
    @Column(name = "description_ckb", length = 1000) private String descriptionCkb;
    @Column(name = "description_kmr", length = 1000) private String descriptionKmr;
    @Column(name = "video_url", nullable = false, length = 2000) private String videoUrl;
    @Column(name = "poster_url", length = 2000) private String posterUrl;
    @Column(name = "display_order") @Builder.Default private Integer displayOrder = 0;
    @Builder.Default private boolean active = true;
}
