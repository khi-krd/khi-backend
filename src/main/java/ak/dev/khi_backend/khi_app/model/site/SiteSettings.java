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

    /**
     * Uploaded typeface for the Sorani (CKB) pages of the website — an
     * uploaded font file (woff2/ttf/otf) stored in the media bucket, loaded
     * there through the same-origin font proxy. Nullable: no upload means the
     * bundled Vazirmatn keeps rendering.
     *
     * <p>{@code ckbFontName} is a display label only ("Rabar", "NRT"…), shown
     * in the dashboard so the editor can tell which file is active.</p>
     */
    @Column(name = "ckb_font_url", length = 1200)
    private String ckbFontUrl;

    @Column(name = "ckb_font_name", length = 200)
    private String ckbFontName;

    /**
     * Same pair for the Kurmanji (ku, Latin-script) pages. Nullable: no upload
     * means Archivo/Clash Display keep rendering.
     */
    @Column(name = "kmr_font_url", length = 1200)
    private String kmrFontUrl;

    @Column(name = "kmr_font_name", length = 200)
    private String kmrFontName;

    /**
     * Admin-picked surface colors, hex strings like {@code #F7F4EC}. Every one
     * nullable: null means the website's bundled token keeps rendering, which is
     * also the reset path — clearing the field restores the default look.
     */
    @Column(name = "body_color", length = 20)
    private String bodyColor;

    @Column(name = "navbar_color", length = 20)
    private String navbarColor;

    @Column(name = "footer_color", length = 20)
    private String footerColor;

    @Column(name = "collection_color", length = 20)
    private String collectionColor;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
