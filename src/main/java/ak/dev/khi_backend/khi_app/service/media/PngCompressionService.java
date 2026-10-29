package ak.dev.khi_backend.khi_app.service.media;

import ak.dev.khi_backend.khi_app.exceptions.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * PngCompressionService — validation + lossless optimisation for every PNG
 * that reaches object storage (wired into {@code S3Service}, the single write
 * path for all uploads).
 *
 * <h2>Validation (always on, streaming, bounded memory)</h2>
 * Format is decided by the signature bytes, never by the filename or the
 * client MIME type. A file that claims PNG but is not PNG is rejected. The
 * validator walks every chunk, checks each CRC, sanity-checks IHDR (bit depth
 * / colour type, dimension and pixel caps) and inflates the image data with a
 * hard cap at the exact expected raw size — so corrupted streams and
 * decompression bombs are rejected without ever decoding a full raster into
 * the heap.
 *
 * <h2>Optimisation (lossless only)</h2>
 * {@code optipng} re-encodes the stream (filters, deflate, exact bit-depth /
 * palette reductions). Every pixel and alpha value is preserved, ancillary
 * chunks (colour profiles, text) are kept, dimensions never change, the
 * format stays PNG. The smaller valid file wins — a frontend-optimised PNG
 * that optipng cannot beat is stored byte-for-byte. Lossy colour reduction is
 * deliberately NOT done here: it is an explicit, previewed choice in the
 * dashboard, never a hidden server-side degradation.
 *
 * <p>Optimisation degrades gracefully: binary missing, file over the
 * processing caps, timeout or busy → the validated original is stored.
 */
@Slf4j
@Service
public class PngCompressionService {

    public static final String PNG_CONTENT_TYPE = "image/png";
    private static final byte[] SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    public enum Method { ORIGINAL, LOSSLESS }

    /** Facts read from a validated PNG. */
    public record PngInfo(int width, int height, int bitDepth, int colorType,
                          boolean interlaced, boolean animated) {
        public long pixels() {
            return (long) width * height;
        }
    }

    /**
     * Outcome of {@link #process(Path)}. {@code file} is either the input path
     * (unchanged) or a temp file owned by this result — call {@link #close()}.
     */
    public record PngResult(Path file, PngInfo info, Method method,
                            long originalSize, long finalSize,
                            long targetBytes, boolean targetMet,
                            Path ownedTemp) implements AutoCloseable {
        public long savedBytes() {
            return originalSize - finalSize;
        }

        @Override
        public void close() {
            deleteRecursively(ownedTemp);
        }
    }

    /** In-memory variant for the byte[] upload paths. */
    public record PngBytesResult(byte[] bytes, PngInfo info, Method method,
                                 long originalSize, long finalSize,
                                 long targetBytes, boolean targetMet) {}

    private final boolean optimizeEnabled;
    private final long targetBytes;
    private final boolean enforceTarget;
    private final int maxDimension;
    private final long maxPixels;
    private final long optimizeMaxBytes;
    private final long optimizeMaxPixels;
    private final int optimizerLevel;
    private final long optimizerTimeoutSeconds;
    private final String optipngBinary;
    private final Semaphore optimizerSlots;
    private volatile Boolean optipngAvailable;

    public PngCompressionService(
            @Value("${app.media.png.optimize:true}") boolean optimizeEnabled,
            @Value("${app.media.png.target-bytes:256000}") long targetBytes,
            @Value("${app.media.png.enforce-target:false}") boolean enforceTarget,
            @Value("${app.media.png.max-dimension:20000}") int maxDimension,
            @Value("${app.media.png.max-pixels:100000000}") long maxPixels,
            @Value("${app.media.png.optimize-max-bytes:67108864}") long optimizeMaxBytes,
            @Value("${app.media.png.optimize-max-pixels:50000000}") long optimizeMaxPixels,
            @Value("${app.media.png.optimizer-level:2}") int optimizerLevel,
            @Value("${app.media.png.optimizer-timeout-seconds:90}") long optimizerTimeoutSeconds,
            @Value("${app.media.png.optimizer-max-concurrent:2}") int optimizerMaxConcurrent,
            @Value("${app.media.png.optipng-binary:optipng}") String optipngBinary
    ) {
        this.optimizeEnabled = optimizeEnabled;
        this.targetBytes = Math.max(0, targetBytes);
        this.enforceTarget = enforceTarget;
        this.maxDimension = maxDimension;
        this.maxPixels = maxPixels;
        this.optimizeMaxBytes = optimizeMaxBytes;
        this.optimizeMaxPixels = optimizeMaxPixels;
        this.optimizerLevel = Math.max(0, Math.min(7, optimizerLevel));
        this.optimizerTimeoutSeconds = optimizerTimeoutSeconds;
        this.optipngBinary = optipngBinary;
        this.optimizerSlots = new Semaphore(Math.max(1, optimizerMaxConcurrent), true);
    }

    /** Defaults identical to the property defaults — for tests and tools. */
    public static PngCompressionService withDefaults() {
        return new PngCompressionService(true, 256_000, false, 20_000, 100_000_000L,
                64L * 1024 * 1024, 50_000_000L, 2, 90, 2, "optipng");
    }

    // ============================================================
    // DETECTION
    // ============================================================

    public static boolean hasPngSignature(byte[] head) {
        if (head == null || head.length < SIGNATURE.length) return false;
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (head[i] != SIGNATURE[i]) return false;
        }
        return true;
    }

    /** The client CLAIMS PNG (MIME or extension) — must then really be PNG. */
    public static boolean claimsPng(String contentType, String filename) {
        boolean byType = contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith(PNG_CONTENT_TYPE);
        boolean byName = filename != null
                && filename.toLowerCase(Locale.ROOT).endsWith(".png");
        return byType || byName;
    }

    /** True when the upload must go through {@link #process}: real PNG bytes,
     * or a PNG claim that the bytes then have to back up. */
    public static boolean isCandidate(byte[] head, String contentType, String filename) {
        return hasPngSignature(head) || claimsPng(contentType, filename);
    }

    // ============================================================
    // PROCESS
    // ============================================================

    public PngBytesResult process(byte[] bytes) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("khi-png-in-", ".png");
            Files.write(tmp, bytes);
            try (PngResult r = process(tmp)) {
                byte[] out = r.method() == Method.ORIGINAL ? bytes : Files.readAllBytes(r.file());
                return new PngBytesResult(out, r.info(), r.method(), r.originalSize(),
                        out.length, r.targetBytes(), r.targetMet());
            }
        } catch (IOException e) {
            throw new BadRequestException("s3.upload.failed", "Failed to buffer PNG: " + e.getMessage());
        } finally {
            deleteRecursively(tmp);
        }
    }

    /** Validate (throws {@link BadRequestException} on invalid input), then
     * optimise losslessly when worthwhile. Never returns a larger file. */
    public PngResult process(Path input) {
        long originalSize = size(input);
        PngInfo info = validate(input);

        Path optimizedDir = optimize(input, info, originalSize);
        Path file = input;
        Method method = Method.ORIGINAL;
        if (optimizedDir != null) {
            file = optimizedDir.resolve("out.png");
            method = Method.LOSSLESS;
        }
        long finalSize = size(file);
        boolean met = targetBytes == 0 || finalSize <= targetBytes;

        log.info("🖼️ PNG {}x{}: {} → {} bytes ({}), target {} {}",
                info.width(), info.height(), originalSize, finalSize, method,
                targetBytes, met ? "met" : "not met");

        if (!met && enforceTarget) {
            deleteRecursively(optimizedDir);
            throw new BadRequestException("media.png.too_large", Map.of(
                    "finalSize", finalSize,
                    "targetBytes", targetBytes,
                    "width", info.width(),
                    "height", info.height()));
        }
        return new PngResult(file, info, method, originalSize, finalSize, targetBytes, met, optimizedDir);
    }

    // ============================================================
    // VALIDATION
    // ============================================================

    public PngInfo validate(Path file) {
        try (InputStream raw = Files.newInputStream(file)) {
            return validate(raw);
        } catch (IOException e) {
            throw invalid("unreadable file");
        }
    }

    /** Streaming structural validation — reads each chunk once, holds at most
     * one 64 KB buffer, inflates IDAT against a hard size cap. */
    public PngInfo validate(InputStream raw) {
        DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 64 * 1024));
        Inflater inflater = new Inflater();
        try {
            byte[] sig = new byte[8];
            in.readFully(sig);
            if (!hasPngSignature(sig)) throw invalid("not PNG data");

            PngInfo header = null;
            boolean animated = false;
            boolean sawIdat = false;
            boolean idatEnded = false;
            boolean sawPrevIdat = false;
            long expectedRaw = 0;
            long inflated = 0;
            byte[] buf = new byte[64 * 1024];
            byte[] sink = new byte[64 * 1024];
            CRC32 crc = new CRC32();

            while (true) {
                long length = Integer.toUnsignedLong(in.readInt());
                if (length > Integer.MAX_VALUE) throw invalid("chunk length out of range");
                byte[] typeBytes = new byte[4];
                in.readFully(typeBytes);
                String type = new String(typeBytes, StandardCharsets.US_ASCII);
                for (byte b : typeBytes) {
                    if (!((b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z'))) {
                        throw invalid("bad chunk type");
                    }
                }
                if (header == null && !"IHDR".equals(type)) throw invalid("IHDR must come first");

                crc.reset();
                crc.update(typeBytes);
                boolean isIdat = "IDAT".equals(type);
                if (isIdat && idatEnded) throw invalid("non-consecutive IDAT chunks");
                if (!isIdat && sawPrevIdat) idatEnded = true;
                sawPrevIdat = isIdat;

                byte[] ihdr = "IHDR".equals(type) ? new byte[13] : null;
                if (ihdr != null && (length != 13 || header != null)) throw invalid("bad IHDR");

                long remaining = length;
                int ihdrPos = 0;
                while (remaining > 0) {
                    int n = (int) Math.min(buf.length, remaining);
                    in.readFully(buf, 0, n);
                    crc.update(buf, 0, n);
                    if (ihdr != null) {
                        System.arraycopy(buf, 0, ihdr, ihdrPos, n);
                        ihdrPos += n;
                    }
                    if (isIdat) {
                        inflater.setInput(buf, 0, n);
                        while (!inflater.needsInput() && !inflater.finished()) {
                            int got = inflater.inflate(sink);
                            inflated += got;
                            if (inflated > expectedRaw) throw invalid("image data exceeds declared size");
                            if (got == 0 && inflater.needsDictionary()) throw invalid("corrupt image data");
                        }
                    }
                    remaining -= n;
                }
                long storedCrc = Integer.toUnsignedLong(in.readInt());
                if (storedCrc != crc.getValue()) throw invalid("CRC mismatch in " + type);

                if (ihdr != null) {
                    header = parseIhdr(ihdr);
                    expectedRaw = expectedRawSize(header);
                } else if ("acTL".equals(type)) {
                    animated = true;
                } else if (isIdat) {
                    sawIdat = true;
                } else if ("IEND".equals(type)) {
                    break;
                }
            }

            if (!sawIdat) throw invalid("no image data");
            if (!inflater.finished() || inflated != expectedRaw) throw invalid("truncated or corrupt image data");
            return new PngInfo(header.width(), header.height(), header.bitDepth(),
                    header.colorType(), header.interlaced(), animated);
        } catch (EOFException e) {
            throw invalid("truncated file");
        } catch (DataFormatException e) {
            throw invalid("corrupt image data");
        } catch (IOException e) {
            throw invalid("unreadable file");
        } finally {
            inflater.end();
        }
    }

    private PngInfo parseIhdr(byte[] b) {
        int width = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        int height = ((b[4] & 0xFF) << 24) | ((b[5] & 0xFF) << 16) | ((b[6] & 0xFF) << 8) | (b[7] & 0xFF);
        int bitDepth = b[8] & 0xFF;
        int colorType = b[9] & 0xFF;
        int compression = b[10] & 0xFF;
        int filter = b[11] & 0xFF;
        int interlace = b[12] & 0xFF;

        if (width <= 0 || height <= 0) throw invalid("invalid dimensions");
        if (!validDepth(colorType, bitDepth)) throw invalid("invalid bit depth / colour type");
        if (compression != 0 || filter != 0 || interlace > 1) throw invalid("unsupported PNG header");
        if (width > maxDimension || height > maxDimension || (long) width * height > maxPixels) {
            throw new BadRequestException("media.png.dimensions_exceeded", Map.of(
                    "width", width, "height", height,
                    "maxDimension", maxDimension, "maxPixels", maxPixels));
        }
        return new PngInfo(width, height, bitDepth, colorType, interlace == 1, false);
    }

    private static boolean validDepth(int colorType, int depth) {
        return switch (colorType) {
            case 0 -> depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16;
            case 3 -> depth == 1 || depth == 2 || depth == 4 || depth == 8;
            case 2, 4, 6 -> depth == 8 || depth == 16;
            default -> false;
        };
    }

    private static int channels(int colorType) {
        return switch (colorType) {
            case 2 -> 3;
            case 4 -> 2;
            case 6 -> 4;
            default -> 1;
        };
    }

    /** Exact decompressed IDAT size: per scanline one filter byte + packed
     * pixels, summed over the 7 Adam7 passes when interlaced. */
    static long expectedRawSize(PngInfo h) {
        int bpp = channels(h.colorType()) * h.bitDepth();
        if (!h.interlaced()) {
            return (long) h.height() * (1 + ((long) h.width() * bpp + 7) / 8);
        }
        int[][] passes = {{0, 0, 8, 8}, {4, 0, 8, 8}, {0, 4, 4, 8}, {2, 0, 4, 4},
                {0, 2, 2, 4}, {1, 0, 2, 2}, {0, 1, 1, 2}};
        long total = 0;
        for (int[] p : passes) {
            long w = (h.width() - p[0] + p[2] - 1) / p[2];
            long rows = (h.height() - p[1] + p[3] - 1) / p[3];
            if (w <= 0 || rows <= 0) continue;
            total += rows * (1 + (w * bpp + 7) / 8);
        }
        return total;
    }

    private static BadRequestException invalid(String reason) {
        return new BadRequestException("media.png.invalid", Map.of("reason", reason));
    }

    // ============================================================
    // OPTIMISATION
    // ============================================================

    /** Returns a temp dir holding {@code out.png} when a strictly smaller,
     * valid, same-size PNG was produced; otherwise null. */
    private Path optimize(Path input, PngInfo info, long originalSize) {
        if (!optimizeEnabled) return null;
        if (targetBytes > 0 && originalSize <= targetBytes) return null;
        // optipng would flatten an APNG to its first frame.
        if (info.animated()) return null;
        if (originalSize > optimizeMaxBytes || info.pixels() > optimizeMaxPixels) {
            log.info("PNG {}x{} ({} bytes) over optimisation caps — stored as validated original",
                    info.width(), info.height(), originalSize);
            return null;
        }
        if (!isOptipngAvailable()) return null;

        boolean acquired = false;
        Path dir = null;
        try {
            acquired = optimizerSlots.tryAcquire(30, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("PNG optimiser busy — storing validated original");
                return null;
            }
            dir = Files.createTempDirectory("khi-png-");
            Path out = dir.resolve("out.png");
            List<String> cmd = List.of(optipngBinary, "-quiet", "-o" + optimizerLevel,
                    "-out", out.toString(), "--", input.toString());
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            boolean done = p.waitFor(optimizerTimeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                log.warn("optipng timed out after {}s — storing validated original", optimizerTimeoutSeconds);
                deleteRecursively(dir);
                return null;
            }
            if (p.exitValue() != 0 || !Files.exists(out) || Files.size(out) >= originalSize) {
                deleteRecursively(dir);
                return null;
            }
            PngInfo outInfo = validate(out);
            if (outInfo.width() != info.width() || outInfo.height() != info.height()) {
                log.error("optipng changed dimensions — discarding output");
                deleteRecursively(dir);
                return null;
            }
            return dir;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteRecursively(dir);
            return null;
        } catch (IOException | BadRequestException e) {
            log.warn("PNG optimisation failed ({}) — storing validated original", e.getMessage());
            deleteRecursively(dir);
            return null;
        } finally {
            if (acquired) optimizerSlots.release();
        }
    }

    public boolean isOptipngAvailable() {
        if (optipngAvailable == null) {
            try {
                Process p = new ProcessBuilder(optipngBinary, "-v").redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                optipngAvailable = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
            } catch (IOException e) {
                optipngAvailable = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (!optipngAvailable) log.warn("optipng unavailable — PNGs are validated but not optimised");
        }
        return optipngAvailable;
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            throw new BadRequestException("s3.upload.failed", "Failed to read upload: " + e.getMessage());
        }
    }

    static void deleteRecursively(Path path) {
        if (path == null) return;
        try {
            if (Files.isDirectory(path)) {
                try (var entries = Files.list(path)) {
                    entries.forEach(PngCompressionService::deleteRecursively);
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // temp file — the OS reclaims it
        }
    }
}
