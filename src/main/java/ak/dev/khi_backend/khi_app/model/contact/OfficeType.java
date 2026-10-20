package ak.dev.khi_backend.khi_app.model.contact;

import jakarta.persistence.*;
import lombok.*;

/**
 * Managed office-type vocabulary — the names offered by the dashboard office
 * picker on contact pages. Contact rows keep their type as a plain text
 * column ({@code office_type}); a rename here is pushed onto the matching
 * contact rows, while a delete only removes the suggestion — the text on
 * existing offices is left untouched.
 */
@Entity
@Table(
        name = "office_types",
        indexes = {
                @Index(name = "idx_office_type_name_ckb", columnList = "name_ckb")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class OfficeType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name_ckb", nullable = false, unique = true, length = 120)
    private String nameCkb;

    @Column(name = "name_kmr", nullable = false, length = 120)
    private String nameKmr;
}
