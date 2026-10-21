package ak.dev.khi_backend.khi_app.service.media;

import ak.dev.khi_backend.khi_app.repository.publishment.image.ImageCollectionRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.sound.SoundTrackRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.video.VideoRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.writing.WritingRepository;
import ak.dev.khi_backend.khi_app.service.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-click media maintenance for the whole library, run in the background:
 *
 * <ol>
 *   <li><b>faststart</b> — every MP4/MOV/M4V/M4A referenced anywhere in the
 *       database whose moov index trails the payload is backed up to
 *       {@code <base>/_backup/…} and re-packed in place (same URL, lossless).</li>
 *   <li><b>cache</b> — every object under the base folder gets the immutable
 *       {@code Cache-Control} header.</li>
 *   <li><b>metadata</b> — duration / resolution / format / size are measured
 *       for every video, clip, source, sound file, brochure, attachment,
 *       album image and book file, then caches are cleared.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaMaintenanceService {

    private static final Pattern MEDIA_URL = Pattern.compile(
            "https?://[^\\s\"'<>,\\\\]+?\\.(?:mp4|mov|m4v|m4a)(?=[\\s\"'<>,\\\\?#]|$)",
            Pattern.CASE_INSENSITIVE);

    private final S3Service s3Service;
    private final MediaProcessingService processing;
    private final MediaMetadataService metadata;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CacheManager cacheManager;
    private final VideoRepository videoRepository;
    private final SoundTrackRepository soundTrackRepository;
    private final ImageCollectionRepository imageCollectionRepository;
    private final WritingRepository writingRepository;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "media-maintenance");
        t.setDaemon(true);
        return t;
    });

    private final List<String> logLines = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean running;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private final Map<String, Integer> counters = Collections.synchronizedMap(new LinkedHashMap<>());

    public synchronized Map<String, Object> start(Set<String> tasks) {
        if (running) return status();
        running = true;
        startedAt = Instant.now();
        finishedAt = null;
        logLines.clear();
        counters.clear();
        executor.submit(() -> {
            try {
                if (tasks.contains("faststart")) runFaststart();
                if (tasks.contains("cache")) runCacheHeaders();
                if (tasks.contains("metadata")) runMetadata();
                note("done");
            } catch (Exception e) {
                log.error("Media maintenance failed", e);
                note("FAILED: " + e.getMessage());
            } finally {
                running = false;
                finishedAt = Instant.now();
            }
        });
        return status();
    }

    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("running", running);
        s.put("startedAt", startedAt);
        s.put("finishedAt", finishedAt);
        s.put("ffmpeg", processing.isFfmpegAvailable());
        s.put("ffprobe", processing.isFfprobeAvailable());
        synchronized (counters) {
            s.put("counters", new LinkedHashMap<>(counters));
        }
        synchronized (logLines) {
            int from = Math.max(0, logLines.size() - 200);
            s.put("log", new ArrayList<>(logLines.subList(from, logLines.size())));
        }
        return s;
    }

    // ─── 1. Faststart ───────────────────────────────────────────────────────

    private void runFaststart() {
        Set<String> urls = referencedMediaUrls();
        note("faststart: " + urls.size() + " MP4-family URLs referenced");
        String backupPrefix = s3Service.getBaseFolder() + "/_backup/";
        for (String url : urls) {
            if (!s3Service.isOurS3Url(url)) {
                bump("faststart.external");
                continue;
            }
            String key = s3Service.extractKeyFromUrl(url);
            if (key == null || key.startsWith(backupPrefix)) continue;
            Path original = null;
            Path packed = null;
            try {
                original = s3Service.downloadToTemp(key);
                if (!processing.needsFaststart(original)) {
                    bump("faststart.already");
                    continue;
                }
                packed = processing.faststartIfNeeded(original, key);
                if (packed.equals(original)) {
                    bump("faststart.failed");
                    note("faststart: remux failed for " + key);
                    continue;
                }
                String contentType = s3Service.head(key).contentType();
                String backupKey = backupPrefix + key.substring(s3Service.getBaseFolder().length() + 1);
                s3Service.copy(key, backupKey);
                s3Service.replaceFromFile(key, packed,
                        contentType != null ? contentType : "video/mp4");
                bump("faststart.fixed");
                note("faststart: fixed " + key + " (backup " + backupKey + ")");
            } catch (Exception e) {
                bump("faststart.failed");
                note("faststart: error on " + key + ": " + e.getMessage());
            } finally {
                deleteQuietly(original);
                if (packed != null && !packed.equals(original)) deleteQuietly(packed);
            }
        }
    }

    /** Every MP4-family URL in any text/json column of the public schema. */
    private Set<String> referencedMediaUrls() {
        Set<String> urls = new TreeSet<>();
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT table_name, column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' "
                        + "AND data_type IN ('text', 'character varying', 'json', 'jsonb')");
        for (Map<String, Object> col : columns) {
            String table = (String) col.get("table_name");
            String column = (String) col.get("column_name");
            String sql = "SELECT \"" + column + "\"::text FROM \"" + table + "\" WHERE \""
                    + column + "\"::text ~* '\\.(mp4|mov|m4v|m4a)'";
            try {
                for (String value : jdbc.queryForList(sql, String.class)) {
                    if (value == null) continue;
                    Matcher m = MEDIA_URL.matcher(value);
                    while (m.find()) urls.add(m.group());
                }
            } catch (Exception e) {
                log.debug("Skipped {}.{}: {}", table, column, e.getMessage());
            }
        }
        return urls;
    }

    // ─── 2. Cache headers ───────────────────────────────────────────────────

    private void runCacheHeaders() {
        List<String> keys = s3Service.listKeys();
        note("cache: " + keys.size() + " objects");
        for (String key : keys) {
            try {
                var head = s3Service.head(key);
                if (S3Service.CACHE_CONTROL.equals(head.cacheControl())) {
                    bump("cache.already");
                    continue;
                }
                s3Service.stampCacheControl(key, head.contentType() != null
                        ? head.contentType() : "application/octet-stream");
                bump("cache.stamped");
            } catch (Exception e) {
                bump("cache.failed");
                note("cache: error on " + key + ": " + e.getMessage());
            }
        }
    }

    // ─── 3. Metadata ────────────────────────────────────────────────────────

    private void runMetadata() {
        recompute("videos", videoRepository.findEveryId(), id -> videoRepository.findById(id)
                .ifPresent(v -> { metadata.applyToVideo(v); videoRepository.save(v); }));
        recompute("sounds", soundTrackRepository.findEveryId(), id -> soundTrackRepository.findById(id)
                .ifPresent(s -> { metadata.applyToSoundTrack(s); soundTrackRepository.save(s); }));
        recompute("collections", imageCollectionRepository.findEveryId(), id -> imageCollectionRepository.findById(id)
                .ifPresent(c -> { metadata.applyToCollection(c); imageCollectionRepository.save(c); }));
        recompute("writings", writingRepository.findEveryId(), id -> writingRepository.findById(id)
                .ifPresent(w -> { metadata.applyToWriting(w); writingRepository.save(w); }));
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        });
        note("metadata: caches cleared");
    }

    private void recompute(String label, List<Long> ids, Consumer<Long> work) {
        note("metadata: " + label + " (" + ids.size() + ")");
        for (Long id : ids) {
            try {
                tx.executeWithoutResult(status -> work.accept(id));
                bump("metadata." + label);
            } catch (Exception e) {
                bump("metadata." + label + ".failed");
                note("metadata: " + label + " #" + id + " failed: " + e.getMessage());
            }
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private void note(String line) {
        log.info("[media-maintenance] {}", line);
        logLines.add(Instant.now() + " " + line);
    }

    private void bump(String counter) {
        counters.merge(counter, 1, Integer::sum);
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignored) {
            // temp file
        }
    }
}
