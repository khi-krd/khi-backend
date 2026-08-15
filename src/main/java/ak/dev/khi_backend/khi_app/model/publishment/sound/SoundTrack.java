package ak.dev.khi_backend.khi_app.model.publishment.sound;

import ak.dev.khi_backend.khi_app.enums.Language;
import ak.dev.khi_backend.khi_app.enums.publishment.TrackState;
import ak.dev.khi_backend.khi_app.model.publishment.topic.PublishmentTopic;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(
        name = "sound_tracks",
        indexes = {
                @Index(name = "idx_soundtrack_type",       columnList = "sound_type"),
                @Index(name = "idx_soundtrack_state",      columnList = "track_state"),
                @Index(name = "idx_soundtrack_album",      columnList = "is_album_of_memories"),
                @Index(name = "idx_soundtrack_topic",      columnList = "topic_id"),
                @Index(name = "idx_soundtrack_created_at", columnList = "created_at"),
                @Index(name = "idx_soundtrack_updated_at", columnList = "updated_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SoundTrack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ─── Cover Images ─────────────────────────────────────────────────────────

    @Column(name = "ckb_cover_url", length = 1000)
    private String ckbCoverUrl;

    @Column(name = "kmr_cover_url", length = 1000)
    private String kmrCoverUrl;

    @Column(name = "hover_cover_url", length = 1000)
    private String hoverCoverUrl;

    // ─── Sound Type ───────────────────────────────────────────────────────────

    @Column(name = "sound_type", nullable = false, length = 100)
    private String soundType;

    // ─── Track State ──────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "track_state", nullable = false, length = 10)
    private TrackState trackState;

    @Builder.Default
    @Column(name = "is_album_of_memories", nullable = false)
    private boolean albumOfMemories = false;

    // ─── Topic ────────────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "topic_id")
    private PublishmentTopic topic;

    // ─── Content Languages ────────────────────────────────────────────────────

    @Builder.Default
    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "sound_track_content_languages",
            joinColumns = @JoinColumn(name = "sound_track_id")
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "language", nullable = false, length = 10)
    private Set<Language> contentLanguages = new LinkedHashSet<>();

    // ─── Embedded Bilingual Content ───────────────────────────────────────────

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(
                    name = "title",
                    column = @Column(name = "title_ckb", length = 200)
            ),
            @AttributeOverride(
                    name = "description",
                    column = @Column(name = "description_ckb", columnDefinition = "TEXT")
            )
    })
    private SoundTrackContent ckbContent;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(
                    name = "title",
                    column = @Column(name = "title_kmr", length = 200)
            ),
            @AttributeOverride(
                    name = "description",
                    column = @Column(name = "description_kmr", columnDefinition = "TEXT")
            )
    })
    private SoundTrackContent kmrContent;

    // ─── Locations ────────────────────────────────────────────────────────────

    @Builder.Default
    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "sound_track_locations",
            joinColumns = @JoinColumn(name = "sound_track_id")
    )
    @Column(name = "location", nullable = false, length = 255)
    private Set<String> locations = new LinkedHashSet<>();

    // ─── Reader / Performer ───────────────────────────────────────────────────

    @Column(name = "reader_name", length = 255)
    private String reader;

    // ─── Directors ────────────────────────────────────────────────────────────

    @Builder.Default
    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "sound_track_directors",
            joinColumns = @JoinColumn(name = "sound_track_id")
    )
    @Column(name = "director_name", nullable = false, length = 255)
    private Set<String> directors = new LinkedHashSet<>();

    // ─── Terms / Dialect ──────────────────────────────────────────────────────

    @Column(name = "terms", length = 200)
    private String terms;

    @Column(name = "is_institute_project", nullable = false)
    private boolean thisProjectOfInstitute;

    // ─── CKB Keywords ─────────────────────────────────────────────────────────

    @Builder.Default
    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "sound_track_keywords_ckb",
            joinColumns = @JoinColumn(name = "sound_track_id")
    )
    @Column(name = "keyword_ckb", nullable = false, length = 100)
    private Set<String> keywordsCkb = new LinkedHashSet<>();

    // ─── KMR Keywords ─────────────────────────────────────────────────────────

    @Builder.Default
    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "sound_track_keywords_kmr",
            joinColumns = @JoinColumn(name = "sound_track_id")
    )
    @Column(name = "keyword_kmr", nullable = false, length = 100)
    private Set<String> keywordsKmr = new LinkedHashSet<>();

    // ─── CKB Tags ─────────────────────────────────────────────────────────────

    @Builder.Default
    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "sound_track_tags_ckb",
            joinColumns = @JoinColumn(name = "sound_track_id")
    )
    @Column(name = "tag_ckb", nullable = false, length = 60)
    private Set<String> tagsCkb = new LinkedHashSet<>();

    // ─── KMR Tags ─────────────────────────────────────────────────────────────

    @Builder.Default
    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "sound_track_tags_kmr",
            joinColumns = @JoinColumn(name = "sound_track_id")
    )
    @Column(name = "tag_kmr", nullable = false, length = 60)
    private Set<String> tagsKmr = new LinkedHashSet<>();

    // ─── Audio Files ──────────────────────────────────────────────────────────
    //
    // IMPORTANT:
    // This is Set, not List.
    // Hibernate cannot fetch two List bags at the same time.
    // Using Set prevents MultipleBagFetchException.

    @Builder.Default
    @BatchSize(size = 50)
    @OneToMany(
            mappedBy = "soundTrack",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    @OrderBy("id ASC")
    private Set<SoundTrackFile> files = new LinkedHashSet<>();

    // ─── Multi-Album Fields ───────────────────────────────────────────────────

    @Column(name = "album_name", length = 300)
    private String albumName;

    @Column(name = "publishment_year")
    private Integer publishmentYear;

    @Column(name = "cd_number")
    private Integer cdNumber;

    @Column(name = "total_tracks")
    private Integer totalTracks;

    // ─── Attachments ──────────────────────────────────────────────────────────
    //
    // IMPORTANT:
    // This is Set, not List.
    // This fixes the crash when files and attachments are fetched together.

    @Builder.Default
    @BatchSize(size = 50)
    @OneToMany(
            mappedBy = "soundTrack",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    @OrderBy("id ASC")
    private Set<SoundTrackAttachment> attachments = new LinkedHashSet<>();

    // ─── Timestamps ───────────────────────────────────────────────────────────

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder.Default
    @Column(name = "featured", nullable = false)
    private boolean featured = false;

    @Column(name = "featured_order")
    private Integer featuredOrder;

    /** Optional wide picture for the homepage hero; falls back to the cover when null. */
    @Column(name = "feature_image_url", columnDefinition = "TEXT")
    private String featureImageUrl;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    public void addFile(SoundTrackFile file) {
        files.add(file);
        file.setSoundTrack(this);
    }

    public void removeFile(SoundTrackFile file) {
        files.remove(file);
        file.setSoundTrack(null);
    }

    public void addAttachment(SoundTrackAttachment attachment) {
        attachments.add(attachment);
        attachment.setSoundTrack(this);
    }

    public void removeAttachment(SoundTrackAttachment attachment) {
        attachments.remove(attachment);
        attachment.setSoundTrack(null);
    }

    public boolean isMultiAlbumOfMemories() {
        return trackState == TrackState.MULTI && albumOfMemories;
    }

    public boolean isMulti() {
        return trackState == TrackState.MULTI;
    }
}