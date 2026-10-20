package ak.dev.khi_backend.khi_app.model.publishment.sound;

import jakarta.persistence.*;
import lombok.*;

/**
 * Managed sound-type vocabulary — the names offered by the dashboard type
 * picker. Tracks keep their type as a plain text column ({@code sound_type});
 * a rename here is pushed onto the matching track rows, while a delete only
 * removes the suggestion — the text on existing tracks is left untouched.
 */
@Entity
@Table(
        name = "sound_track_types",
        indexes = {
                @Index(name = "idx_sound_track_type_name_ckb", columnList = "name_ckb")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class SoundTrackType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name_ckb", nullable = false, unique = true, length = 120)
    private String nameCkb;

    @Column(name = "name_kmr", nullable = false, length = 120)
    private String nameKmr;
}
