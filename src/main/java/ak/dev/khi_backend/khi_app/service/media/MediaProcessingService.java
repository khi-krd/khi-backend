package ak.dev.khi_backend.khi_app.service.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * MediaProcessingService — ffmpeg/ffprobe helpers for uploaded media.
 *
 * <ul>
 *   <li><b>Faststart</b>: MP4/MOV/M4A files written with the {@code moov}
 *       index AFTER the {@code mdat} payload force the browser to fetch the
 *       file's tail before playback can begin. {@link #faststartIfNeeded}
 *       detects that layout by walking the top-level atoms and remuxes
 *       losslessly ({@code -c copy -movflags +faststart}).</li>
 *   <li><b>Probe</b>: {@link #probe(String)} reads duration / resolution /
 *       format / size / audio details from a URL or local path via ffprobe,
 *       so editors never type file metadata by hand.</li>
 * </ul>
 *
 * Every method degrades gracefully: when ffmpeg is missing or a command
 * fails, the original file is kept and the probe is empty.
 */
@Slf4j
@Service
public class MediaProcessingService {

    private static final long FFMPEG_TIMEOUT_MIN = 20;
    private static final long FFPROBE_TIMEOUT_SEC = 45;
    private static final int PROBE_CACHE_SIZE = 4000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile Boolean ffmpegAvailable;
    private volatile Boolean ffprobeAvailable;

    /** URL → probe. Object keys are UUID-unique, so a URL's bytes never change. */
    private final Map<String, MediaProbe> probeCache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, MediaProbe> eldest) {
                    return size() > PROBE_CACHE_SIZE;
                }
            });

    // =========================================================================
    // FASTSTART
    // =========================================================================

    /** MP4-family containers that carry a moov atom. */
    public boolean isFaststartCandidate(String contentType, String filename) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.equals("video/mp4") || ct.equals("video/quicktime") || ct.equals("video/x-m4v")
                || ct.equals("audio/mp4") || ct.equals("audio/x-m4a") || ct.equals("audio/m4a")) {
            return true;
        }
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        return name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".m4v")
                || name.endsWith(".m4a");
    }

    /**
     * Returns a faststart copy of {@code input} when the moov atom trails the
     * payload, else {@code input} itself. The caller deletes any returned path
     * that differs from the input.
     */
    public Path faststartIfNeeded(Path input, String filename) {
        try {
            if (!needsFaststart(input)) return input;
            if (!isFfmpegAvailable()) {
                log.warn("ffmpeg unavailable — uploading {} without faststart", filename);
                return input;
            }
            String ext = extensionOf(filename, ".mp4");
            Path out = Files.createTempFile("khi-faststart-", ext);
            List<String> cmd = List.of("ffmpeg", "-y", "-v", "error", "-i", input.toString(),
                    "-map", "0", "-c", "copy", "-movflags", "+faststart", out.toString());
            long started = System.currentTimeMillis();
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String output = readAll(p.getInputStream());
            boolean done = p.waitFor(FFMPEG_TIMEOUT_MIN, TimeUnit.MINUTES);
            if (!done || p.exitValue() != 0 || Files.size(out) == 0) {
                if (!done) p.destroyForcibly();
                log.warn("Faststart remux failed for {} (exit={}): {}", filename,
                        done ? p.exitValue() : "timeout", output.strip());
                Files.deleteIfExists(out);
                return input;
            }
            log.info("Faststart remux for {} in {} ms ({} → {} bytes)", filename,
                    System.currentTimeMillis() - started, Files.size(input), Files.size(out));
            return out;
        } catch (Exception e) {
            log.warn("Faststart skipped for {}: {}", filename, e.getMessage());
            return input;
        }
    }

    /** True when a top-level {@code mdat} atom appears before {@code moov}. */
    public boolean needsFaststart(Path file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            long pos = 0;
            byte[] header = new byte[16];
            while (pos + 8 <= length) {
                raf.seek(pos);
                raf.readFully(header, 0, 8);
                long size = ((header[0] & 0xFFL) << 24) | ((header[1] & 0xFFL) << 16)
                        | ((header[2] & 0xFFL) << 8) | (header[3] & 0xFFL);
                String type = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
                if (size == 1) {
                    if (pos + 16 > length) return false;
                    raf.readFully(header, 8, 8);
                    size = 0;
                    for (int i = 8; i < 16; i++) size = (size << 8) | (header[i] & 0xFFL);
                } else if (size == 0) {
                    size = length - pos;
                }
                if ("moov".equals(type)) return false;
                if ("mdat".equals(type)) return true;
                if (size < 8) return false;
                pos += size;
            }
            return false;
        }
    }

    // =========================================================================
    // PROBE
    // =========================================================================

    /**
     * Probe a direct media URL or local path. Results are cached per URL.
     * Returns an empty probe for YouTube/embeds or when ffprobe is missing.
     */
    public MediaProbe probe(String source) {
        if (source == null || source.isBlank()) return MediaProbe.empty();
        String src = source.trim();
        if (!isDirectFile(src)) return MediaProbe.empty();
        MediaProbe cached = probeCache.get(src);
        if (cached != null) return cached;
        MediaProbe probe = runProbe(src);
        if (!probe.isEmpty()) probeCache.put(src, probe);
        return probe;
    }

    /** http(s) URL ending in a media extension, or an existing local path. */
    public boolean isDirectFile(String src) {
        String lower = src.toLowerCase(Locale.ROOT);
        if (lower.contains("youtube.com") || lower.contains("youtu.be")
                || lower.contains("vimeo.com") || lower.contains("soundcloud.com")) {
            return false;
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            String path = lower.split("[?#]")[0];
            return path.matches(".*\\.(mp4|m4v|mov|webm|mkv|avi|ogv|mp3|m4a|wav|ogg|oga|aac|flac|opus"
                    + "|jpe?g|png|webp|gif|avif|bmp|tiff?|heic|pdf)$");
        }
        return Files.exists(Path.of(src));
    }

    private MediaProbe runProbe(String src) {
        if (!isFfprobeAvailable()) return MediaProbe.empty();
        try {
            List<String> cmd = new ArrayList<>(List.of("ffprobe", "-v", "error",
                    "-print_format", "json", "-show_format", "-show_streams"));
            if (src.startsWith("http")) {
                cmd.addAll(List.of("-rw_timeout", "30000000"));
            }
            cmd.add(src);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            String json = readAll(p.getInputStream());
            boolean done = p.waitFor(FFPROBE_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                log.warn("ffprobe timed out for {}", src);
                return MediaProbe.empty();
            }
            if (p.exitValue() != 0 || json.isBlank()) {
                log.debug("ffprobe failed for {} (exit={})", src, p.exitValue());
                return MediaProbe.empty();
            }
            return parse(objectMapper.readTree(json), src);
        } catch (Exception e) {
            log.warn("ffprobe error for {}: {}", src, e.getMessage());
            return MediaProbe.empty();
        }
    }

    private MediaProbe parse(JsonNode root, String src) {
        JsonNode format = root.path("format");
        Integer width = null, height = null, sampleRate = null, channels = null;
        Long streamBitRate = null;
        boolean hasVideo = false, hasAudio = false;
        for (JsonNode s : root.path("streams")) {
            String type = s.path("codec_type").asText("");
            // Cover art in audio files is a 1-frame "video" stream — skip it.
            boolean attachedPic = s.path("disposition").path("attached_pic").asInt(0) == 1;
            if ("video".equals(type) && !attachedPic && width == null) {
                hasVideo = true;
                width = positive(s.path("width").asInt(0));
                height = positive(s.path("height").asInt(0));
            } else if ("audio".equals(type) && !hasAudio) {
                hasAudio = true;
                sampleRate = positive(s.path("sample_rate").asInt(0));
                channels = positive(s.path("channels").asInt(0));
                long br = s.path("bit_rate").asLong(0);
                streamBitRate = br > 0 ? br : null;
            }
        }
        double duration = format.path("duration").asDouble(0);
        long size = format.path("size").asLong(0);
        long bitRate = format.path("bit_rate").asLong(0);
        String formatName = format.path("format_name").asText("");

        boolean isStill = hasVideo && !hasAudio && duration <= 0.05;
        return MediaProbe.builder()
                .durationSeconds(!isStill && duration > 0.05 ? (int) Math.round(duration) : null)
                .width(width)
                .height(height)
                .sizeBytes(size > 0 ? size : null)
                .bitRate(hasAudio && !hasVideo && streamBitRate != null ? streamBitRate
                        : (bitRate > 0 ? bitRate : null))
                .sampleRate(sampleRate)
                .channels(channels)
                .fileFormat(fileFormatOf(src, formatName))
                .hasVideo(hasVideo && !isStill)
                .hasAudio(hasAudio)
                .build();
    }

    /** Extension-first ("mp4", "jpg"), falling back to ffprobe's format name. */
    private static String fileFormatOf(String src, String formatName) {
        String path = src.split("[?#]")[0];
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot > slash && dot < path.length() - 1) {
            String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (ext.length() <= 5) return ext.equals("jpeg") ? "jpg" : ext;
        }
        if (formatName == null || formatName.isBlank()) return null;
        return formatName.split(",")[0];
    }

    /** Content-Length of a remote file via HEAD (documents ffprobe can't read). */
    public Optional<Long> remoteSize(String url) {
        if (url == null || !url.trim().toLowerCase(Locale.ROOT).startsWith("http")) {
            return Optional.empty();
        }
        try {
            java.net.http.HttpResponse<Void> res = httpClient().send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(url.trim()))
                            .method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody())
                            .timeout(java.time.Duration.ofSeconds(15))
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() >= 400) return Optional.empty();
            return res.headers().firstValueAsLong("content-length").stream().boxed()
                    .filter(v -> v > 0).findFirst();
        } catch (Exception e) {
            log.debug("HEAD failed for {}: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    private volatile java.net.http.HttpClient httpClient;

    private java.net.http.HttpClient httpClient() {
        if (httpClient == null) {
            httpClient = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();
        }
        return httpClient;
    }

    // =========================================================================
    // INTERNALS
    // =========================================================================

    public boolean isFfmpegAvailable() {
        if (ffmpegAvailable == null) ffmpegAvailable = commandWorks("ffmpeg");
        return ffmpegAvailable;
    }

    public boolean isFfprobeAvailable() {
        if (ffprobeAvailable == null) ffprobeAvailable = commandWorks("ffprobe");
        return ffprobeAvailable;
    }

    private static boolean commandWorks(String binary) {
        try {
            Process p = new ProcessBuilder(binary, "-version").redirectErrorStream(true).start();
            readAll(p.getInputStream());
            boolean ok = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
            if (!ok) log.warn("{} is not available — media processing disabled", binary);
            return ok;
        } catch (Exception e) {
            log.warn("{} is not available — media processing disabled ({})", binary, e.getMessage());
            return false;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Integer positive(int v) {
        return v > 0 ? v : null;
    }

    private static String extensionOf(String filename, String fallback) {
        if (filename == null) return fallback;
        int dot = filename.lastIndexOf('.');
        return dot >= 0 && dot < filename.length() - 1
                ? filename.substring(dot).toLowerCase(Locale.ROOT) : fallback;
    }

    // =========================================================================
    // RESULT
    // =========================================================================

    @Getter
    @Builder
    public static class MediaProbe {
        private final Integer durationSeconds;
        private final Integer width;
        private final Integer height;
        private final Long sizeBytes;
        private final Long bitRate;
        private final Integer sampleRate;
        private final Integer channels;
        private final String fileFormat;
        private final boolean hasVideo;
        private final boolean hasAudio;

        public static MediaProbe empty() {
            return MediaProbe.builder().build();
        }

        public boolean isEmpty() {
            return durationSeconds == null && width == null && sizeBytes == null && fileFormat == null;
        }

        /** "1920x1080" or null. */
        public String resolution() {
            return width != null && height != null ? width + "x" + height : null;
        }

        /** Size in MB, one decimal. */
        public Double sizeMb() {
            return sizeBytes == null ? null : Math.round(sizeBytes / 104857.6) / 10.0;
        }

        public Optional<Integer> duration() {
            return Optional.ofNullable(durationSeconds);
        }
    }
}
