package ak.dev.khi_backend.khi_app.model.publishment.sound;

import ak.dev.khi_backend.khi_app.enums.publishment.AudioChannel;
import ak.dev.khi_backend.khi_app.enums.publishment.FileType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;

/**
 * SoundTrackFile — a single audio file (or external link) attached to a SoundTrack.
 *
 * ─── File identity ────────────────────────────────────────────────────────────
 *  · fileUrl      – direct hosted URL (S3, CDN …)      optional
 *  · externalUrl  – external page link (YouTube, etc.) optional
 *  · embedUrl     – iframe/embed URL                   optional
 *  At least one of the three should be provided.
 *
 * ─── Technical metadata (NEW) ─────────────────────────────────────────────────
 *  · fileFormat   – e.g. "MP3", "FLAC", "WAV"
 *  · sizeBytes    – file size in bytes  (set automatically by service layer)
 *  · durationSeconds – duration in seconds (set automatically by service layer)
 *  · bitRate      – e.g. "24-bit", "320 kbps"
 *  · sampleRate   – e.g. "44100 Hz", "200 Hz"
 *  · audioChannel – STEREO / MONO  (AudioChannel enum)
 *
 * ─── Content metadata (NEW) ───────────────────────────────────────────────────
 *  · title           – title of this specific file/track
 *  · publishmentYear – year the file was published
 *  · form            – musical/linguistic form (کێشدار، بێکەش، بێکێش و کێشدار …)
 *  · genre           – genre label
 *  · recordingVenue  – place where the sound was recorded
 *
 * ─── Brochures (NEW) ──────────────────────────────────────────────────────────
 *  · brochures – ordered list of brochure/cover images for this file.
 *    Each entry is a SoundTrackBrochure entity with an image URL.
 */
@Entity
@Table(
        name = "sound_track_files",
        indexes = {
                @Index(name = "idx_sound_file_type",  columnList = "file_type"),
                @Index(name = "idx_sound_file_track",  columnList = "sound_track_id")
        }
)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SoundTrackFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ─── File Locations ───────────────────────────────────────────────────────

    /** Direct hosted file URL (S3, local, CDN …). Optional. */
    @Column(name = "file_url", length = 1200)
    private String fileUrl;

    /** External page link (e.g. YouTube watch, SoundCloud page). Optional. */
    @Column(name = "external_url", columnDefinition = "TEXT")
    private String externalUrl;

    /** Embed / iframe URL. Optional. */
    @Column(name = "embed_url", columnDefinition = "TEXT")
    private String embedUrl;

    // ─── File Identity ────────────────────────────────────────────────────────

    /** Title of this individual file / track. */
    @Column(name = "title", length = 300)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false, length = 10)
    private FileType fileType;

    /**
     * Year this particular file was published.
     * (The album-level year lives on SoundTrack.publishmentYear for MULTI tracks.)
     */
    @Column(name = "publishment_year")
    private Integer publishmentYear;

    /**
     * File container/codec format label.
     * e.g. "MP3", "FLAC", "WAV", "AAC", "OGG".
     * Store as a plain string so no enum migration is needed
     * when new formats are supported.
     */
    @Column(name = "file_format", length = 50)
    private String fileFormat;

    // ─── Technical Audio Metadata ─────────────────────────────────────────────

    /**
     * File size in bytes.
     * Set automatically by the service layer when the file is uploaded.
     * Use 0 when unknown.
     */
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /**
     * Duration in seconds.
     * Set automatically by the service layer (e.g. via audio metadata library).
     * Use 0 when unknown.
     * Tip: expose as minutes in the DTO: durationSeconds / 60.0
     */
    @Column(name = "duration_seconds", nullable = false)
    private long durationSeconds;

    /**
     * Bit depth or bitrate label.
     * e.g. "24-bit", "16-bit", "320 kbps", "128 kbps".
     */
    @Column(name = "bit_rate", length = 50)
    private String bitRate;

    /**
     * Sample rate label.
     * e.g. "44100 Hz", "48000 Hz", "200 Hz".
     */
    @Column(name = "sample_rate", length = 50)
    private String sampleRate;

    /**
     * Audio channel layout.
     * STEREO or MONO — see AudioChannel enum.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "audio_channel", length = 10)
    private AudioChannel audioChannel;

    // ─── Content / Style Metadata ─────────────────────────────────────────────

    /**
     * Musical/linguistic form of the sound.
     * Typical Kurdish values: کێشدار، بێکەش، بێکێش و کێشدار
     * Stored as free text so it can hold any future value without a migration.
     */
    @Column(name = "form", length = 150)
    private String form;

    /**
     * Genre label.
     * e.g. "Folk", "Classical", "Religious", "Poetry".
     */
    @Column(name = "genre", length = 100)
    private String genre;

    /**
     * The physical or virtual location where the sound was recorded.
     * e.g. "Studio A, Sulaymaniyah", "National Theatre – Baghdad".
     */
    @Column(name = "recording_venue", length = 500)
    private String recordingVenue;

    /**
     * Position of this file inside the parent track's file list.
     * Lower values render first. Set by the service layer from the
     * submitted list position (or explicit sortOrder).
     */
    @Column(name = "sort_order")
    private Integer sortOrder;

    // ─── Brochures ────────────────────────────────────────────────────────────
    //
    // NEW: ordered list of brochure image URLs for this file.
    // @BatchSize ensures Hibernate loads all brochures for a batch of files
    // in a single IN-query rather than one query per file.

    @Builder.Default
    @BatchSize(size = 50)
    @OneToMany(
            mappedBy = "soundTrackFile",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    @OrderBy("brochureOrder ASC, id ASC")
    private List<SoundTrackBrochure> brochures = new ArrayList<>();

    // ─── Owner ────────────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sound_track_id", nullable = false)
    private SoundTrack soundTrack;

    // ─── Helpers ──────────────────────────────────────────────────────────────

    public void addBrochure(SoundTrackBrochure brochure) {
        brochures.add(brochure);
        brochure.setSoundTrackFile(this);
    }

    public void removeBrochure(SoundTrackBrochure brochure) {
        brochures.remove(brochure);
        brochure.setSoundTrackFile(null);
    }

    /**
     * Convenience: returns duration as decimal minutes.
     * e.g. 90 seconds → 1.5 minutes.
     */
    public double getDurationMinutes() {
        return durationSeconds / 60.0;
    }
}