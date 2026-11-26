package ak.dev.khi_backend.khi_app.model.publishment.sound;

import ak.dev.khi_backend.khi_app.model.publishment.writing.ManagedOption;
import jakarta.persistence.*;
import lombok.*;

/**
 * جۆری دەنگ — the editor-managed type of a sound record (تاک, ئەلبووم and
 * whatever admins add later). Names only: the editor structure still follows
 * the structural {@code TrackState} — the seeded SINGLE row maps to one file,
 * every other row to the album layout.
 */
@Entity
@Table(name = "sound_states", uniqueConstraints = {
        @UniqueConstraint(name = "uk_sound_state_slug", columnNames = "slug")
})
@NoArgsConstructor
public class SoundState extends ManagedOption {
}
