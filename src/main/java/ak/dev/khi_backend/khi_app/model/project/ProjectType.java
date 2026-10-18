package ak.dev.khi_backend.khi_app.model.project;

import jakarta.persistence.*;
import lombok.*;

/**
 * Managed project-type vocabulary — the names offered by the dashboard type
 * picker. Projects keep their type as plain text columns
 * ({@code project_type_ckb} / {@code project_type_kmr}); a rename here is
 * pushed onto the matching project rows, while a delete only removes the
 * suggestion — the text on existing projects is left untouched.
 */
@Entity
@Table(
        name = "project_types",
        indexes = {
                @Index(name = "idx_project_type_name_ckb", columnList = "name_ckb")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ProjectType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name_ckb", nullable = false, unique = true, length = 120)
    private String nameCkb;

    @Column(name = "name_kmr", nullable = false, length = 120)
    private String nameKmr;
}
