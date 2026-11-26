package ak.dev.khi_backend.khi_app.dto.publishment.writing;

import ak.dev.khi_backend.khi_app.enums.Language;
import java.io.Serializable;
import ak.dev.khi_backend.khi_app.enums.publishment.BookGenre;
import ak.dev.khi_backend.khi_app.enums.publishment.WritingFileFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
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

        private String title;

        private String description;

        private String fileUrl;

        private WritingFileFormat fileFormat;

        private Long fileSizeBytes;

        private Integer pageCount;

        private String genre;

        private String location;
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
    public static class TopicPayload implements Serializable  {
        private String nameCkb;

        private String nameKmr;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class TopicInfo implements Serializable  {
        private Long   id;
        private String nameCkb;
        private String nameKmr;
    }

    // =========================================================================
    // BOOK GENRE DTOS (editor-managed rows, /api/v1/book-genres)
    // =========================================================================

    /**
     * One genre chip on a book response — the linked BookGenre row.
     */
    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class GenreInfo {
        private Long   id;
        private String slug;
        private String nameCkb;
        private String nameKmr;
    }

    /**
     * Create/replace a genre row. At least one of nameCkb / nameKmr must be
     * non-blank, and the slug format (UPPERCASE letters/digits/underscore) —
     * enforced in the service. Blank names are saved as null.
     */
    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class BookGenreRequest {
        private String slug;
        private String nameCkb;
        private String nameKmr;
        private Integer displayOrder;
        private Boolean active;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class BookGenreResponse {
        private Long    id;
        private String  slug;
        private String  nameCkb;
        private String  nameKmr;
        private Integer displayOrder;
        private Boolean active;
        /** How many books link to this genre — the dashboard's delete warning. */
        private Long    bookCount;
    }

    // =========================================================================
    // MANAGED OPTION DTOS (writing types / editions / book languages)
    // =========================================================================

    /**
     * Shared create/replace body for the three single-select vocabularies —
     * same fields as {@link BookGenreRequest}.
     */
    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class ManagedOptionRequest {
        private String slug;
        private String nameCkb;
        private String nameKmr;
        private Integer displayOrder;
        private Boolean active;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class ManagedOptionResponse {
        private Long    id;
        private String  slug;
        private String  nameCkb;
        private String  nameKmr;
        private Integer displayOrder;
        private Boolean active;
        /** How many writings point at this row — the dashboard's delete warning. */
        private Long    bookCount;
    }

    /** The option row as it appears embedded on a writing response. */
    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class ManagedOptionInfo implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private Long   id;
        private String slug;
        private String nameCkb;
        private String nameKmr;
    }

    /** Compact book reference — original-book / later-edition links, now
     *  carrying the display fields the public editions block needs. */
    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class BookLinkInfo {
        private Long   id;
        private String titleCkb;
        private String titleKmr;
        /** That book's own edition row (چاپی دووەم…). */
        private ManagedOptionInfo edition;
        private String coverUrl;
        private String ckbFileUrl;
        private String kmrFileUrl;
        private java.time.LocalDateTime createdAt;
    }

    // =========================================================================
    // SERIES DTOS
    // =========================================================================

    // =========================================================================
    // PEOPLE (writer, collector, translator, ...)
    // =========================================================================

    /** One person entry in a create/update payload — no id, order = list order. */
    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class PersonPayload {
        private String roleCkb;
        private String roleKmr;
        private String nameCkb;
        private String nameKmr;
        private String nicknameCkb;
        private String nicknameKmr;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class PersonDto {
        private Long id;
        private String roleCkb;
        private String roleKmr;
        private String nameCkb;
        private String nameKmr;
        private String nicknameCkb;
        private String nicknameKmr;
        private Integer sortOrder;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class SeriesInfoDto {
        private String  seriesId;
        private String  seriesName;
        private Double  seriesOrder;
        private Long    parentBookId;
        /** Effective total — max(root's plannedTotal, memberCount). */
        private Integer totalBooks;
        /** Editor-declared total kept on the ROOT book only. */
        private Integer plannedTotal;
        /** How many member rows the series actually has right now. */
        private Integer memberCount;
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
        private String        ckbCoverUrl;
        private String        kmrCoverUrl;
        private String        hoverCoverUrl;
        /** True on the primary/root book of the chain. */
        private boolean       isParent;
        private LocalDateTime createdAt;
    }

    // =========================================================================
    // CREATE REQUEST
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class CreateRequest {

        private Set<Language> contentLanguages;

        // ─── Cover Images (3 slots) ───────────────────────────────────────────

        private String ckbCoverUrl;

        private String kmrCoverUrl;

        private String hoverCoverUrl;

        // ─── Language Content ─────────────────────────────────────────────────

        private LanguageContentDto ckbContent;
        private LanguageContentDto kmrContent;

        // ─── Topics ───────────────────────────────────────────────────────────

        private Long topicId;
        private TopicPayload newTopic;
        private Set<Long> topicIds;
        private List<TopicPayload> newTopics;

        // ─── Book Genres (multiple) ───────────────────────────────────────────

        /**
         * Ids of BookGenre rows for this book — the preferred way to set genres.
         * At least one genre is required (via genreIds or legacy bookGenres) —
         * enforced in the service, where the two fields are reconciled.
         */
        private Set<Long> genreIds;

        /**
         * Legacy alternative to genreIds, kept for one release so the dashboard
         * can migrate: enum codes (e.g. ["HISTORY", "NOVEL"]) resolved against
         * BookGenre.slug. Ignored when genreIds is non-empty.
         */
        private Set<BookGenre> bookGenres;

        // ─── Shared ───────────────────────────────────────────────────────────

        private String bookLanguageCkb;

        private String bookLanguageKmr;

        /** Managed زمانی کتێب row id — takes precedence over the text fields. */
        private Long bookLanguageId;

        // ─── Classification + edition chain ───────────────────────────────────

        /** جۆری نووسین row id; absent → the seeded BOOK row when it exists. */
        private Long writingTypeId;

        /** چاپ row id; absent → the seeded FIRST_EDITION row when it exists. */
        private Long editionId;

        /** First-edition book this record is a later چاپ of. */
        private Long originalBookId;

        private boolean publishedByInstitute = true;

        // ─── Audio + CD cover ─────────────────────────────────────────────────

        /** External audio URL — ignored when the audioFile part is sent. */
        private String audioUrl;

        /** External CD-cover URL — ignored when the cdCoverImage part is sent. */
        private String cdCoverUrl;

        private BilingualSet tags;
        private BilingualSet keywords;

        // ─── Series ───────────────────────────────────────────────────────────

        private String seriesId;

        /** Ignored — the series name always derives from the root book's title. */
        private String seriesName;

        private Double seriesOrder;

        private Long parentBookId;

        /** Declares this book the PRIMARY (first) book of a new series. */
        private Boolean startSeries;

        /** Root-only: the editor's declared "N books" total for the chain. */
        private Integer seriesTotalBooks;

        // ─── People ───────────────────────────────────────────────────────────

        private List<PersonPayload> persons;
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

        // ─── Topics ───────────────────────────────────────────────────────────

        private Long topicId;
        private TopicPayload newTopic;
        private Boolean clearTopic;
        private Set<Long> topicIds;
        private List<TopicPayload> newTopics;

        // ─── Book Genres (multiple) ───────────────────────────────────────────

        /**
         * Replace the current genre set with these BookGenre row ids — the
         * preferred way. Nullable — omit or pass null/empty to leave the
         * current genres unchanged.
         */
        private Set<Long> genreIds;

        /**
         * Legacy alternative to genreIds (enum codes resolved against
         * BookGenre.slug), kept for one release. Ignored when genreIds is
         * non-empty; null/empty leaves the current genres unchanged.
         */
        private Set<BookGenre> bookGenres;

        // ─── Shared ───────────────────────────────────────────────────────────

        private String bookLanguageCkb;

        private String bookLanguageKmr;

        /**
         * Managed زمانی کتێب row id — replaces the linked row. Null leaves the
         * link unchanged; {@code clearBookLanguage} detaches it.
         */
        private Long bookLanguageId;
        private Boolean clearBookLanguage;

        // ─── Classification + edition chain ───────────────────────────────────

        /** جۆری نووسین row id — replaces the linked row. */
        private Long writingTypeId;
        private Boolean clearWritingType;

        /** چاپ row id — replaces the linked row. */
        private Long editionId;
        private Boolean clearEdition;

        /** Re-link this record to a different original (first-edition) book. */
        private Long originalBookId;

        /** Explicit detach from the original book — like unlinkFromSeries. */
        private Boolean clearOriginalBook;

        private Boolean publishedByInstitute;

        // ─── Audio + CD cover ─────────────────────────────────────────────────

        /** "" clears, a URL replaces, null keeps — same merge as cover urls. */
        private String audioUrl;
        private String cdCoverUrl;

        private BilingualSet tags;
        private BilingualSet keywords;

        // ─── Series ───────────────────────────────────────────────────────────

        /** Ignored — the series name always derives from the root book's title. */
        private String seriesName;

        private Double seriesOrder;

        private Long parentBookId;

        /** Promote this book to the PRIMARY (first) book of a series. */
        private Boolean startSeries;

        /** Root-only: the editor's declared "N books" total for the chain. */
        private Integer seriesTotalBooks;

        /** Detach this book from its series entirely (explicit — unlike parentBookId:null). */
        private Boolean unlinkFromSeries;

        // ─── People ───────────────────────────────────────────────────────────

        /**
         * Full replace when non-null — like the services contents rewrite.
         * Omit to leave the current people unchanged.
         */
        private List<PersonPayload> persons;
    }

    // =========================================================================
    // RESPONSE
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class Response {

        private Long id;

        /** Manual list position — written through {@code PUT /api/v1/writings/order}. */
        private Integer sortOrder;

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

        // ─── Topics ───────────────────────────────────────────────────────────

        /** Primary (first) linked topic — kept for backward compatibility. */
        private TopicInfo topic;

        /** Every linked topic, in selection order. */
        private List<TopicInfo> topics;

        // ─── Book Genres (multiple) ───────────────────────────────────────────

        /**
         * Backward-compatible genre codes — the linked BookGenre rows' slugs.
         * Serialises exactly like the old enum array, so the website keeps
         * working unchanged. Always a non-null set (may be empty for legacy data).
         */
        private Set<String> bookGenres;

        /**
         * The full linked genre rows (id, slug and bilingual names) — what the
         * website will switch to for labels. Always non-null, may be empty.
         */
        private List<GenreInfo> genres;

        // ─── Shared ───────────────────────────────────────────────────────────

        private String bookLanguageCkb;

        private String bookLanguageKmr;

        /** Managed زمانی کتێب row, when linked. */
        private ManagedOptionInfo bookLanguage;

        // ─── Classification + edition chain ───────────────────────────────────

        private ManagedOptionInfo writingType;
        private ManagedOptionInfo edition;

        /** The original (first-edition) book this record is a later چاپ of. */
        private BookLinkInfo originalBook;

        /** Later editions that point back at this original. */
        private List<BookLinkInfo> editionBooks;

        private boolean publishedByInstitute;

        // ─── Audio + CD cover ─────────────────────────────────────────────────

        private String audioUrl;
        private String cdCoverUrl;

        private BilingualSet tags;
        private BilingualSet keywords;

        // ─── People ───────────────────────────────────────────────────────────

        private List<PersonDto> persons;

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
        /** Effective total — max(root's plannedTotal, memberCount). */
        private Integer             totalBooks;
        /** Actual member rows right now. */
        private Integer             memberCount;
        private List<SeriesBookSummary> books;
    }

    // =========================================================================
    // LINK TO SERIES REQUEST
    // =========================================================================

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class LinkToSeriesRequest {

        private Long bookId;

        private Long parentBookId;

        private Double seriesOrder;

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
