package ak.dev.khi_backend.khi_app.model.publishment.writing;

import jakarta.persistence.*;
import lombok.*;

/**
 * One editor-managed book genre — the chips on the writings page and the genre
 * list on every book. Replaces the fixed {@code enums.publishment.BookGenre}
 * enum; the enum stays only as a request-compatibility shim until the dashboard
 * and website have switched to genre ids/slugs.
 *
 * The slug is the stable machine key (UPPERCASE, letters/digits/underscore).
 * It appears in website URLs and in the backward-compatible {@code bookGenres}
 * string array on book responses, and it never changes once books link to the
 * row — renaming a genre means changing its names, not its slug.
 *
 * Books link to genres through the {@code book_genre_links} join table owned by
 * {@link Writing#getGenres()}.
 */
@Entity
@Table(name = "book_genres", uniqueConstraints = {
        @UniqueConstraint(name = "uk_book_genre_slug", columnNames = "slug")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BookGenre {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 60) private String slug;
    @Column(name = "name_ckb", length = 200) private String nameCkb;
    @Column(name = "name_kmr", length = 200) private String nameKmr;
    @Column(name = "display_order") @Builder.Default private Integer displayOrder = 0;
    @Builder.Default private boolean active = true;
}
