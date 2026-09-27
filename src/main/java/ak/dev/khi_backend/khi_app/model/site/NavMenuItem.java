package ak.dev.khi_backend.khi_app.model.site;

import jakarta.persistence.*;
import lombok.*;

/**
 * One top-level entry of the website hamburger menu (news, projects, sound, …).
 *
 * <p>{@code itemKey} joins the row to a section in the website's static nav config;
 * the site owns labels, hrefs and secondary links. This table only stores the
 * full-screen background photo behind each section. The legacy label/href columns
 * stay as display data — writes go through {@code PUT} which touches nothing but
 * {@code imageUrl} and {@code active}.</p>
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

    @Column(nullable = false, length = 300)                private String href;
    @Column(name = "image_url", columnDefinition = "TEXT")  private String imageUrl;

    @Column(name = "display_order") @Builder.Default private Integer displayOrder = 0;
    @Builder.Default private boolean active = true;
}
