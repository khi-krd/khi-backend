package ak.dev.khi_backend.khi_app.model.site;

import jakarta.persistence.*;
import lombok.*;

/**
 * A secondary link listed under a {@link NavMenuItem}. Never edited on its own —
 * the whole set is saved with its parent item.
 */
@Entity
@Table(name = "nav_menu_links")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class NavMenuLink {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private NavMenuItem item;

    @Column(name = "label_ckb", nullable = false, length = 200) private String labelCkb;
    @Column(name = "label_kmr", length = 200)                   private String labelKmr;
    @Column(nullable = false, length = 300)                     private String href;

    @Column(name = "display_order") @Builder.Default private Integer displayOrder = 0;
    @Builder.Default private boolean active = true;
}
