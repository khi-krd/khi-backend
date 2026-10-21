package ak.dev.khi_backend.khi_app.service.media;

import ak.dev.khi_backend.khi_app.enums.publishment.AudioChannel;
import ak.dev.khi_backend.khi_app.model.publishment.image.ImageAlbumItem;
import ak.dev.khi_backend.khi_app.model.publishment.sound.SoundAlbumBrochure;
import ak.dev.khi_backend.khi_app.model.publishment.sound.SoundTrackAttachment;
import ak.dev.khi_backend.khi_app.model.publishment.sound.SoundTrackFile;
import ak.dev.khi_backend.khi_app.model.publishment.video.Video;
import ak.dev.khi_backend.khi_app.model.publishment.video.VideoClipItem;
import ak.dev.khi_backend.khi_app.model.publishment.video.VideoSourceFile;
import ak.dev.khi_backend.khi_app.model.publishment.writing.WritingContent;
import ak.dev.khi_backend.khi_app.service.media.MediaProcessingService.MediaProbe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * MediaMetadataService — the server is the single source of truth for file
 * metadata. Every save path calls one of these {@code apply*} methods so
 * duration / resolution / format / size are measured from the stored file
 * itself; whatever the client sent for those fields is only a fallback for
 * sources that cannot be measured (YouTube, embeds, unreachable links).
 *
 * Probes are cached per URL inside {@link MediaProcessingService}, so
 * re-saving a record never re-reads an unchanged file.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaMetadataService {

    private final MediaProcessingService processing;

    // ─── Video ──────────────────────────────────────────────────────────────

    /** FILM: every source + the main-source mirror; VIDEO_CLIP: every clip. */
    public void applyToVideo(Video video) {
        if (video == null) return;
        if (video.getVideoSources() != null) {
            video.getVideoSources().forEach(this::applyToSource);
        }
        if (video.getVideoClipItems() != null) {
            video.getVideoClipItems().forEach(this::applyToClip);
        }
        MediaProbe p = processing.probe(video.getSourceUrl());
        if (p.isEmpty()) return;
        if (p.getDurationSeconds() != null) video.setDurationSeconds(p.getDurationSeconds());
        if (p.resolution() != null) video.setResolution(p.resolution());
        if (p.getFileFormat() != null) video.setFileFormat(p.getFileFormat());
        if (p.sizeMb() != null) video.setFileSizeMb(p.sizeMb());
    }

    public void applyToSource(VideoSourceFile source) {
        if (source == null) return;
        MediaProbe p = processing.probe(source.getUrl());
        if (p.getDurationSeconds() != null) source.setDurationSeconds(p.getDurationSeconds());
    }

    public void applyToClip(VideoClipItem clip) {
        if (clip == null) return;
        MediaProbe p = processing.probe(clip.getUrl());
        if (p.isEmpty()) return;
        if (p.getDurationSeconds() != null) clip.setDurationSeconds(p.getDurationSeconds());
        if (p.resolution() != null) clip.setResolution(p.resolution());
        if (p.getFileFormat() != null) clip.setFileFormat(p.getFileFormat());
        if (p.sizeMb() != null) clip.setFileSizeMb(p.sizeMb());
    }

    // ─── Sound ──────────────────────────────────────────────────────────────

    public void applyToSoundFile(SoundTrackFile file) {
        if (file == null) return;
        MediaProbe p = processing.probe(file.getFileUrl());
        if (p.isEmpty()) return;
        if (p.getDurationSeconds() != null) file.setDurationSeconds(p.getDurationSeconds());
        if (p.getFileFormat() != null) file.setFileFormat(p.getFileFormat());
        if (p.getSizeBytes() != null) file.setSizeBytes(p.getSizeBytes());
        if (p.getBitRate() != null) {
            file.setBitRate(Math.round(p.getBitRate() / 1000.0) + " kbps");
        }
        if (p.getSampleRate() != null) {
            file.setSampleRate(formatSampleRate(p.getSampleRate()));
        }
        if (p.getChannels() != null) {
            file.setAudioChannel(p.getChannels() >= 2 ? AudioChannel.STEREO : AudioChannel.MONO);
        }
    }

    public void applyToBrochure(SoundAlbumBrochure brochure) {
        if (brochure == null) return;
        Long size = sizeOf(brochure.getFileUrl());
        if (size != null) brochure.setSizeBytes(size);
    }

    public void applyToAttachment(SoundTrackAttachment attachment) {
        if (attachment == null) return;
        Long size = sizeOf(attachment.getFileUrl());
        if (size != null) attachment.setSizeBytes(size);
    }

    // ─── Image collections ──────────────────────────────────────────────────

    public void applyToAlbumItem(ImageAlbumItem item) {
        if (item == null) return;
        MediaProbe p = processing.probe(item.getImageUrl());
        if (p.getWidth() != null) item.setWidthPx(p.getWidth());
        if (p.getHeight() != null) item.setHeightPx(p.getHeight());
        Long size = p.getSizeBytes() != null ? p.getSizeBytes() : sizeOf(item.getImageUrl());
        if (size != null) item.setFileSizeBytes(size);
    }

    // ─── Writings ───────────────────────────────────────────────────────────

    public void applyToWritingContent(WritingContent content) {
        if (content == null) return;
        Long size = sizeOf(content.getFileUrl());
        if (size != null) content.setFileSizeBytes(size);
    }

    // ─── Aggregates (one call per save path) ────────────────────────────────

    public void applyToSoundTrack(ak.dev.khi_backend.khi_app.model.publishment.sound.SoundTrack track) {
        if (track == null) return;
        if (track.getFiles() != null) track.getFiles().forEach(this::applyToSoundFile);
        if (track.getBrochures() != null) track.getBrochures().forEach(this::applyToBrochure);
        if (track.getAttachments() != null) track.getAttachments().forEach(this::applyToAttachment);
    }

    public void applyToCollection(ak.dev.khi_backend.khi_app.model.publishment.image.ImageCollection collection) {
        if (collection == null || collection.getImageAlbum() == null) return;
        collection.getImageAlbum().forEach(this::applyToAlbumItem);
    }

    public void applyToWriting(ak.dev.khi_backend.khi_app.model.publishment.writing.Writing writing) {
        if (writing == null) return;
        applyToWritingContent(writing.getCkbContent());
        applyToWritingContent(writing.getKmrContent());
    }

    /** Never let a probe failure break a save — metadata is best-effort. */
    public void safely(Runnable apply) {
        try {
            apply.run();
        } catch (Exception e) {
            log.warn("Metadata probe skipped: {}", e.getMessage());
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    /** Byte size from ffprobe when it can read the file, else HEAD. */
    private Long sizeOf(String url) {
        if (url == null || url.isBlank()) return null;
        MediaProbe p = processing.probe(url);
        if (p.getSizeBytes() != null) return p.getSizeBytes();
        return processing.remoteSize(url).orElse(null);
    }

    private static String formatSampleRate(int hz) {
        double khz = hz / 1000.0;
        return (khz == Math.floor(khz)
                ? String.valueOf((int) khz)
                : String.format(Locale.ROOT, "%.1f", khz)) + " kHz";
    }
}
