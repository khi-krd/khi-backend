package ak.dev.khi_backend.khi_app.model.project;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

/**
 * One selected project type — a bilingual name pair captured at selection
 * time. Projects can carry several of these (multi-select types) while the
 * legacy projectTypeCkb/projectTypeKmr columns keep the primary entry.
 */
@Embeddable
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
@EqualsAndHashCode(of = {"typeCkb", "typeKmr"})
public class ProjectTypeSelection {

    @Column(name = "type_ckb", nullable = false, length = 128)
    private String typeCkb;

    @Column(name = "type_kmr", length = 128)
    private String typeKmr;
}
