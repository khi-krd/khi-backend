package ak.dev.khi_backend.khi_app.dto.publishment.writing;

import ak.dev.khi_backend.khi_app.enums.Language;
import ak.dev.khi_backend.khi_app.enums.publishment.BookGenre;
import ak.dev.khi_backend.khi_app.enums.publishment.WritingFileFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;

public final class WritingDtos {

    private WritingDtos() {}

    // =========================================================================
    // LANGUAGE CONTENT DTO
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class LanguageContentDto {

        @Size(max = 300)
        private String title;

        @Size(max = 10000)
        private String description;

        @Size(max = 200)
        private String writer;

        @Size(max = 1000)
        private String fileUrl;

        private WritingFileFormat fileFormat;

        @Min(0)
        private Long fileSizeBytes;

        @Min(1)
        private Integer pageCount;

        @Size(max = 150)
        private String genre;
    }

    // =========================================================================
    // BILINGUAL SET DTO
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class BilingualSet {
        private Set<String> ckb;
        private Set<String> kmr;
    }

    // =========================================================================
    // TOPIC DTOS
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class TopicPayload {
        @Size(max = 300)
        private String nameCkb;

        @Size(max = 300)
        private String nameKmr;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class TopicInfo {
        private Long   id;
        private String nameCkb;
        private String nameKmr;
    }

    // =========================================================================
    // SERIES DTOS
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class SeriesInfoDto {
        private String  seriesId;
        private String  seriesName;
        private Double  seriesOrder;
        private Long    parentBookId;
        private Integer totalBooks;
        private boolean isParent;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class SeriesBookSummary {
        private Long          id;
        private String        titleCkb;
        private String        titleKmr;
        private Double        seriesOrder;
        private LocalDateTime createdAt;
    }

    // =========================================================================
    // CREATE REQUEST
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class CreateRequest {

        @NotNull
        @NotEmpty(message = "At least one content language is required")
        private Set<Language> contentLanguages;

        // ─── Cover Images (3 slots) ───────────────────────────────────────────

        @Size(max = 2000)
        private String ckbCoverUrl;

        @Size(max = 2000)
        private String kmrCoverUrl;

        @Size(max = 2000)
        private String hoverCoverUrl;

        // ─── Language Content ─────────────────────────────────────────────────

        private LanguageContentDto ckbContent;
        private LanguageContentDto kmrContent;

        // ─── Topic ────────────────────────────────────────────────────────────

        private Long topicId;
        private TopicPayload newTopic;

        // ─── Book Genres (multiple) ───────────────────────────────────────────

        /**
         * One or more genres for this book (e.g. [HISTORY, NOVEL] for a historical novel).
         * At least one genre is required.
         */
        @NotNull(message = "At least one book genre is required")
        @NotEmpty(message = "At least one book genre is required")
        private Set<BookGenre> bookGenres;

        // ─── Shared ───────────────────────────────────────────────────────────

        private boolean publishedByInstitute;

        private BilingualSet tags;
        private BilingualSet keywords;

        // ─── Series ───────────────────────────────────────────────────────────

        @Size(max = 100)
        private String seriesId;

        @Size(max = 300)
        private String seriesName;

        @Min(0)
        private Double seriesOrder;

        private Long parentBookId;
    }

    // =========================================================================
    // UPDATE REQUEST
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class UpdateRequest {

        private Set<Language> contentLanguages;

        // ─── Cover Images (3 slots) ───────────────────────────────────────────

        private String ckbCoverUrl;
        private String kmrCoverUrl;
        private String hoverCoverUrl;

        // ─── Language Content ─────────────────────────────────────────────────

        private LanguageContentDto ckbContent;
        private LanguageContentDto kmrContent;

        // ─── Topic ────────────────────────────────────────────────────────────

        private Long topicId;
        private TopicPayload newTopic;
        private Boolean clearTopic;

        // ─── Book Genres (multiple) ───────────────────────────────────────────

        /**
         * Replace the current genre set with a new one.
         * Nullable — omit or pass null to leave the current genres unchanged.
         * Pass a non-empty set to replace all genres.
         */
        private Set<BookGenre> bookGenres;

        // ─── Shared ───────────────────────────────────────────────────────────

        private Boolean publishedByInstitute;

        private BilingualSet tags;
        private BilingualSet keywords;

        // ─── Series ───────────────────────────────────────────────────────────

        @Size(max = 300)
        private String seriesName;

        @Min(0)
        private Double seriesOrder;

        private Long parentBookId;
    }

    // =========================================================================
    // RESPONSE
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class Response {

        private Long id;

        private Set<Language> contentLanguages;

        // ─── Cover Images (3 slots) ───────────────────────────────────────────

        private String ckbCoverUrl;
        private String kmrCoverUrl;
        private String hoverCoverUrl;
        /** Hero picture for the homepage carousel; written via the featured PATCH. */
        private String featureImageUrl;

        // ─── Language Content ─────────────────────────────────────────────────

        private LanguageContentDto ckbContent;
        private LanguageContentDto kmrContent;

        // ─── Topic ────────────────────────────────────────────────────────────

        private TopicInfo topic;

        // ─── Book Genres (multiple) ───────────────────────────────────────────

        /**
         * All genres assigned to this book.
         * Always a non-null set (may be empty for legacy data).
         */
        private Set<BookGenre> bookGenres;

        // ─── Shared ───────────────────────────────────────────────────────────

        private boolean publishedByInstitute;

        private BilingualSet tags;
        private BilingualSet keywords;

        // ─── Series ───────────────────────────────────────────────────────────

        private SeriesInfoDto seriesInfo;

        /** Public-website compatibility alias for {@link #seriesInfo}. */
        @JsonProperty("series")
        public SeriesInfoDto getSeries() {
            return seriesInfo;
        }

        /** Public-website compatibility aliases for the nested topic fields. */
        @JsonProperty("topicId")
        public Long getTopicId() {
            return topic != null ? topic.getId() : null;
        }

        @JsonProperty("topicNameCkb")
        public String getTopicNameCkb() {
            return topic != null ? topic.getNameCkb() : null;
        }

        @JsonProperty("topicNameKmr")
        public String getTopicNameKmr() {
            return topic != null ? topic.getNameKmr() : null;
        }

        // ─── Timestamps ───────────────────────────────────────────────────────

        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    // =========================================================================
    // SERIES RESPONSE
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class SeriesResponse {
        private String              seriesId;
        private String              seriesName;
        private Integer             totalBooks;
        private List<SeriesBookSummary> books;
    }

    // =========================================================================
    // LINK TO SERIES REQUEST
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class LinkToSeriesRequest {

        @NotNull(message = "Book ID is required")
        private Long bookId;

        @NotNull(message = "Parent book ID is required")
        private Long parentBookId;

        @NotNull(message = "Series order is required")
        @Min(1)
        private Double seriesOrder;

        @Size(max = 300)
        private String seriesName;
    }

    // =========================================================================
    // SEARCH / FILTER REQUEST
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class SearchRequest {
        /** Filter by one or more book genres. */
        private Set<BookGenre> bookGenres;
        private Boolean   instituteOnly;
        private String    writer;
        private String    language;
        private String    seriesId;
        private Boolean   seriesParentsOnly;
    }
}
