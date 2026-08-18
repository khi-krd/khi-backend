package ak.dev.khi_backend.khi_app.model.publishment.video;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * The single, site-wide background video for the homepage Film section.
 *
 * <p>Plays muted and looping behind the film cards. Not a {@link Video}: no bilingual
 * content, no topic, no tags, no featured flag — one file plus its metadata. The
 * repository keeps at most one row, so every endpoint is collection-level and takes
 * no id. Mirrors
 * {@link ak.dev.khi_backend.khi_app.model.publishment.sound.SoundReklamVideo}, which
 * does the same job for the Sound section.</p>
 */
@Entity
@Table(name = "film_reklam_videos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FilmReklamVideo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "video_url", nullable = false, length = 1200)
    private String videoUrl;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
