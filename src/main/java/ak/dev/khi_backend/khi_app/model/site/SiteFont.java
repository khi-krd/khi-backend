package ak.dev.khi_backend.khi_app.model.site;

import ak.dev.khi_backend.khi_app.enums.Language;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One entry of the site typeface library — an uploaded font file (woff2/ttf/…)
 * stored in the media bucket. The library can hold several fonts per language;
 * which one is live is decided by {@code site_settings.ckbFontUrl} /
 * {@code kmrFontUrl}, so the website contract stays unchanged.
 */
@Entity
@Table(name = "site_fonts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SiteFont {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Which site language the face is meant for — CKB (Arabic) or KMR (Latin). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Language language;

    /** Display label shown in the dashboard library ("Rabar", "NRT"…). */
    @Column(nullable = false, length = 200)
    private String name;

    /** Public URL of the uploaded file in the media bucket. */
    @Column(nullable = false, length = 1200)
    private String url;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
