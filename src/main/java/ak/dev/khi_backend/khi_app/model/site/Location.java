package ak.dev.khi_backend.khi_app.model.site;

import jakarta.persistence.*;
import lombok.*;

/**
 * Managed location vocabulary — the options offered by the dashboard's
 * location picker on every content form (news, projects, videos, writings,
 * image collections, sound tracks, services). Content rows keep their
 * location as plain text, so renaming or deleting an option here never
 * rewrites existing records.
 */
@Entity
@Table(
        name = "locations",
        indexes = {
                @Index(name = "idx_location_name_ckb", columnList = "name_ckb")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Location {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name_ckb", nullable = false, unique = true, length = 160)
    private String nameCkb;

    @Column(name = "name_kmr", nullable = false, length = 160)
    private String nameKmr;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;
}
