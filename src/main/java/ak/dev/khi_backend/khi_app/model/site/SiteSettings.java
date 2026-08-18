package ak.dev.khi_backend.khi_app.model.site;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "site_settings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SiteSettings {

    public static final int DEFAULT_MAX_FEATURED_SLIDES = 7;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    @Column(name = "max_featured_slides", nullable = false)
    private Integer maxFeaturedSlides = DEFAULT_MAX_FEATURED_SLIDES;

    /**
     * Institute logo, shown in the header and footer of every page.
     *
     * <p>Nullable: when null the website falls back to its bundled logo, so this is
     * safe to ship before anyone uploads anything. It renders on a cream ground in
     * the header and a near-black ground in the footer, so the uploaded file should
     * be a transparent PNG.</p>
     */
    @Column(name = "logo_url", length = 1200)
    private String logoUrl;

    /**
     * Photograph for the donate band above the footer.
     *
     * <p>Nullable: when null the band renders on a plain dark ground. The same file
     * is shown sharp inside the slanted panel and again blurred behind it.</p>
     */
    @Column(name = "donate_image_url", length = 1200)
    private String donateImageUrl;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
