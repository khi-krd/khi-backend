package ak.dev.khi_backend.khi_app.model.site;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * One top-level entry of the website hamburger menu (news, projects, sound, …).
 *
 * <p>{@code itemKey} is the stable handle the website uses to build the secondary
 * links of the six CMS-backed sections automatically — it must not change once
 * the row exists.</p>
 */
@Entity
@Table(name = "nav_menu_items",
        uniqueConstraints = @UniqueConstraint(name = "uk_nav_item_key", columnNames = "item_key"))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class NavMenuItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_key", nullable = false, length = 60) private String itemKey;

    @Column(name = "label_ckb", nullable = false, length = 200) private String labelCkb;
    @Column(name = "label_kmr", length = 200)                   private String labelKmr;

    @Column(name = "description_ckb", columnDefinition = "TEXT") private String descriptionCkb;
    @Column(name = "description_kmr", columnDefinition = "TEXT") private String descriptionKmr;

    @Column(nullable = false, length = 300)                private String href;
    @Column(name = "image_url", columnDefinition = "TEXT")  private String imageUrl;

    @Column(name = "display_order") @Builder.Default private Integer displayOrder = 0;
    @Builder.Default private boolean active = true;

    /** Secondary links shown under the item. Replaced wholesale on update. */
    @OneToMany(mappedBy = "item", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("displayOrder ASC, id ASC")
    @Builder.Default
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private List<NavMenuLink> links = new ArrayList<>();
}
