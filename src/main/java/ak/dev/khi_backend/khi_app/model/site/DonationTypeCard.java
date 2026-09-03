package ak.dev.khi_backend.khi_app.model.site;

import jakarta.persistence.*;
import lombok.*;

/**
 * One picture card in the donate page's "دەتوانم چی ببەخشم؟ / What can I donate?"
 * mosaic. The website sorts by displayOrder and draws the first card big (with its
 * description); the rest are small tiles. Number chips ("01", "02"…) are derived
 * from the position in the sorted list, never stored.
 *
 * Unlike {@link SocialLink} there is no unique column — editors may create as
 * many cards as they want.
 */
@Entity
@Table(name = "donation_type_cards")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DonationTypeCard {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "title_ckb", length = 200) private String titleCkb;
    @Column(name = "title_kmr", length = 200) private String titleKmr;
    @Column(name = "description_ckb", length = 1000) private String descriptionCkb;
    @Column(name = "description_kmr", length = 1000) private String descriptionKmr;
    @Column(name = "image_url", nullable = false, length = 2000) private String imageUrl;
    @Column(name = "display_order") @Builder.Default private Integer displayOrder = 0;
    @Builder.Default private boolean active = true;
}
