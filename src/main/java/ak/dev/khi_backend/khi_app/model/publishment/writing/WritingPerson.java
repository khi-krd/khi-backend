package ak.dev.khi_backend.khi_app.model.publishment.writing;

import jakarta.persistence.*;
import lombok.*;

/**
 * A person attached to a book — a book may carry several (writer, collector,
 * translator, ...). Name and nickname are bilingual like every other writing
 * field; role is free text entered by the editor per language
 * (نووسەر / nivîskar, کۆکەرەوە / kolekter, ...).
 */
@Entity
@Table(
        name = "writing_persons",
        indexes = {
                @Index(name = "idx_writing_persons_writing", columnList = "writing_id"),
                @Index(name = "idx_writing_persons_name_ckb", columnList = "name_ckb")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class WritingPerson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "writing_id", nullable = false)
    private Writing writing;

    @Column(name = "role_ckb", length = 120)
    private String roleCkb;

    @Column(name = "role_kmr", length = 120)
    private String roleKmr;

    @Column(name = "name_ckb", length = 200)
    private String nameCkb;

    @Column(name = "name_kmr", length = 200)
    private String nameKmr;

    @Column(name = "nickname_ckb", length = 200)
    private String nicknameCkb;

    @Column(name = "nickname_kmr", length = 200)
    private String nicknameKmr;

    @Column(name = "sort_order")
    private Integer sortOrder;
}
