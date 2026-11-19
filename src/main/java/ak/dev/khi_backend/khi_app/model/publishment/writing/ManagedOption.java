package ak.dev.khi_backend.khi_app.model.publishment.writing;

import jakarta.persistence.*;
import lombok.*;

/**
 * Shared shape of an editor-managed single-select vocabulary row — writing
 * types, editions and book languages all carry the same columns. Mirrors
 * {@link BookGenre}: the slug is the stable machine key (UPPERCASE,
 * letters/digits/underscore) and never changes once books link to the row.
 */
@MappedSuperclass
@Data
@NoArgsConstructor
@AllArgsConstructor
public abstract class ManagedOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String slug;

    @Column(name = "name_ckb", length = 200)
    private String nameCkb;

    @Column(name = "name_kmr", length = 200)
    private String nameKmr;

    @Column(name = "display_order")
    private Integer displayOrder = 0;

    private boolean active = true;
}
