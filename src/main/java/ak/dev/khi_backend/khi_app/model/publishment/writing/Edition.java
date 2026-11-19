package ak.dev.khi_backend.khi_app.model.publishment.writing;

import jakarta.persistence.*;
import lombok.*;

/**
 * چاپ — which printing/edition of a book this record is (چاپی یەکەم,
 * چاپی دووەم, …). Editor-managed like {@link BookGenre}; new books default
 * to the FIRST_EDITION row (resolved by slug in the service).
 */
@Entity
@Table(name = "editions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_edition_slug", columnNames = "slug")
})
@NoArgsConstructor
public class Edition extends ManagedOption {
}
