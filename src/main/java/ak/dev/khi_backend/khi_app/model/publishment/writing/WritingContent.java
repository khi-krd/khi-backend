package ak.dev.khi_backend.khi_app.model.publishment.writing;

import ak.dev.khi_backend.khi_app.enums.publishment.WritingFileFormat;
import jakarta.persistence.*;
import lombok.*;

/**
 * Embeddable content for a single language version (CKB or KMR).
 *
 * Holds all language-specific TEXT and FILE fields.
 * Cover images are intentionally excluded — they now live as dedicated
 * columns on the {@link Writing} entity itself:
 *
 *   Writing.ckbCoverUrl   → Sorani   cover
 *   Writing.kmrCoverUrl   → Kurmanji cover
 *   Writing.hoverCoverUrl → Hover overlay
 *
 * This keeps the embeddable focused and avoids any ambiguity when
 * both language blocks are embedded in the same table row.
 */
@Embeddable
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class WritingContent {

    /** Book title in this language. */
    @Column(name = "title", length = 300)
    private String title;

    /** Book description / synopsis in this language. */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * The actual book file URL (PDF, DOCX, EPUB, etc.) for this language version.
     * Stored as S3 URL after upload, or an external URL.
     */
    @Column(name = "file_url", length = 1000)
    private String fileUrl;

    /** File format of the book file for this language version. */
    @Enumerated(EnumType.STRING)
    @Column(name = "file_format", length = 20)
    private WritingFileFormat fileFormat;

    /** File size in bytes for this language version. */
    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    /** Number of pages in this language version. */
    @Column(name = "page_count")
    private Integer pageCount;

    /**
     * Book genre / category in this language.
     * Examples: "Novel", "Short Stories", "Essay Collection", "Poetry"
     */
    @Column(name = "genre", length = 150)
    private String genre;

    /** Place the book was written / published / is about, in this language. */
    @Column(name = "location", length = 255)
    private String location;
}