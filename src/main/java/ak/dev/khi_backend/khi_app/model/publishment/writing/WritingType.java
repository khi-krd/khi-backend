package ak.dev.khi_backend.khi_app.model.publishment.writing;

import jakarta.persistence.*;
import lombok.*;

/**
 * جۆری نووسین — what kind of record this writing is (کتێب, بەڵگەنامە,
 * توێژینەوە, …). Editor-managed like {@link BookGenre}; the seeded defaults
 * live in {@code WritingOptionsSeeder} and admins extend the list.
 */
@Entity
@Table(name = "writing_types", uniqueConstraints = {
        @UniqueConstraint(name = "uk_writing_type_slug", columnNames = "slug")
})
@NoArgsConstructor
public class WritingType extends ManagedOption {
}
