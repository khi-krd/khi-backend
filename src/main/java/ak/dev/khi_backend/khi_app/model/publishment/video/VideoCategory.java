package ak.dev.khi_backend.khi_app.model.publishment.video;

import ak.dev.khi_backend.khi_app.model.publishment.writing.ManagedOption;
import jakarta.persistence.*;
import lombok.*;

/**
 * پۆلی ڤیدیۆ — the editor-managed classification of a video (فیلم, کلیپ,
 * بەرنامەی فۆلکلۆری, چاوپێکەوتن, بەڵگەفیلم, …). Distinct from
 * {@link VideoType}, which stays structural (single FILM vs a VIDEO_CLIP set);
 * this vocabulary is what the public catalog filters and badges show. Seeded
 * by {@code VideoCategorySeeder}; admins extend the list without code changes.
 */
@Entity
@Table(name = "video_categories", uniqueConstraints = {
        @UniqueConstraint(name = "uk_video_category_slug", columnNames = "slug")
})
@NoArgsConstructor
public class VideoCategory extends ManagedOption {
}
