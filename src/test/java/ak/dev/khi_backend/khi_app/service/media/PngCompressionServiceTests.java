package ak.dev.khi_backend.khi_app.service.media;

import ak.dev.khi_backend.khi_app.exceptions.AppException;
import ak.dev.khi_backend.khi_app.exceptions.BadRequestException;
import ak.dev.khi_backend.khi_app.service.media.PngCompressionService.Method;
import ak.dev.khi_backend.khi_app.service.media.PngCompressionService.PngBytesResult;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.Deflater;

import static ak.dev.khi_backend.khi_app.service.media.PngFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PngCompressionServiceTests {

    private static final long TARGET = 256_000;
    private final PngCompressionService service = PngCompressionService.withDefaults();

    private void requireOptipng() {
        assumeTrue(service.isOptipngAvailable(), "optipng not installed");
    }

    private static int[] argbPixels(BufferedImage img) {
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }

    private static String messageKey(Throwable t) {
        return ((AppException) t).getMessageKey();
    }

    // ── small / already-fine ────────────────────────────────────────────────

    @Test
    void smallPngUnderTargetIsStoredByteForByte() {
        byte[] png = rawRgba(32, 32, transparentGradient(32, 32));
        PngBytesResult r = service.process(png);
        assertThat(r.method()).isEqualTo(Method.ORIGINAL);
        assertThat(r.bytes()).isSameAs(png);
        assertThat(r.targetMet()).isTrue();
    }

    @Test
    void alreadyOptimisedPngNeverGrows() {
        requireOptipng();
        byte[] once = service.process(rawRgba(500, 400, photoLike(500, 400, 7))).bytes();
        PngBytesResult twice = service.process(once);
        assertThat(twice.finalSize()).isLessThanOrEqualTo(once.length);
    }

    // ── lossless ───────────────────────────────────────────────────────────

    @Test
    void largeTransparentPngShrinksWithEveryPixelAndAlphaPreserved() throws Exception {
        requireOptipng();
        int w = 600, h = 400;
        int[] px = transparentGradient(w, h);
        byte[] png = rawRgba(w, h, px);
        assertThat(png.length).isGreaterThan((int) TARGET);

        PngBytesResult r = service.process(png);

        assertThat(r.method()).isEqualTo(Method.LOSSLESS);
        assertThat(r.finalSize()).isLessThan(png.length);
        assertThat(PngCompressionService.hasPngSignature(r.bytes())).isTrue();
        BufferedImage out = decode(r.bytes());
        assertThat(out.getWidth()).isEqualTo(w);
        assertThat(out.getHeight()).isEqualTo(h);
        assertThat(argbPixels(out)).isEqualTo(px);
        System.out.printf("[transparent gradient %dx%d] %d -> %d bytes%n", w, h, png.length, r.finalSize());
    }

    @Test
    void photographicPngIsLosslessAndReportsMissedTargetHonestly() throws Exception {
        requireOptipng();
        int w = 800, h = 600;
        int[] px = photoLike(w, h, 1);
        byte[] png = rawRgba(w, h, px);

        PngBytesResult r = service.process(png);

        assertThat(r.finalSize()).isLessThanOrEqualTo(png.length);
        assertThat(argbPixels(decode(r.bytes()))).isEqualTo(px);
        assertThat(r.finalSize()).isGreaterThan(TARGET);
        assertThat(r.targetMet()).isFalse();
        System.out.printf("[photo %dx%d] %d -> %d bytes (target met: %s)%n", w, h, png.length, r.finalSize(), r.targetMet());
    }

    @Test
    void sixteenBitAndPalettePngsStayLossless() throws Exception {
        requireOptipng();
        BufferedImage gray16 = new BufferedImage(700, 500, BufferedImage.TYPE_USHORT_GRAY);
        for (int y = 0; y < 500; y++) for (int x = 0; x < 700; x++) {
            gray16.getRaster().setSample(x, y, 0, (x * 93 + y * 131) & 0xFFFF);
        }
        BufferedImage indexed = new BufferedImage(900, 600, BufferedImage.TYPE_BYTE_INDEXED);
        int[] photo = photoLike(900, 600, 4);
        indexed.setRGB(0, 0, 900, 600, photo, 0, 900);

        for (BufferedImage img : new BufferedImage[]{gray16, indexed}) {
            byte[] png = encodeWithImageIo(img);
            PngBytesResult r = service.process(png);
            BufferedImage out = decode(r.bytes());
            int[] a = img.getRaster().getPixels(0, 0, img.getWidth(), img.getHeight(), (int[]) null);
            if (img.getType() == BufferedImage.TYPE_USHORT_GRAY) {
                int[] b = out.getRaster().getPixels(0, 0, out.getWidth(), out.getHeight(), (int[]) null);
                assertThat(b).isEqualTo(a);
            } else {
                assertThat(argbPixels(out)).isEqualTo(argbPixels(img));
            }
            assertThat(r.finalSize()).isLessThanOrEqualTo(png.length);
        }
    }

    @Test
    void textMetadataChunksAreKept() {
        requireOptipng();
        byte[] text = chunk("tEXt", "Title\0KHI archive".getBytes(StandardCharsets.ISO_8859_1));
        byte[] png = rawRgba(500, 400, transparentGradient(500, 400), text);
        PngBytesResult r = service.process(png);
        assertThat(r.method()).isEqualTo(Method.LOSSLESS);
        assertThat(new String(r.bytes(), StandardCharsets.ISO_8859_1)).contains("KHI archive");
    }

    @Test
    void animatedPngIsPassedThroughUntouched() {
        byte[] actl = chunk("acTL", new byte[]{0, 0, 0, 2, 0, 0, 0, 0});
        byte[] png = rawRgba(400, 400, photoLike(400, 400, 2), actl);
        PngBytesResult r = service.process(png);
        assertThat(r.method()).isEqualTo(Method.ORIGINAL);
        assertThat(r.bytes()).isSameAs(png);
    }

    @Test
    void interlacedPngValidates() throws Exception {
        int w = 13, h = 7;
        // Adam7 raw stream of an all-black RGBA8 image: per pass, rows × (1 + width×4).
        int[][] passes = {{0, 0, 8, 8}, {4, 0, 8, 8}, {0, 4, 4, 8}, {2, 0, 4, 4}, {0, 2, 2, 4}, {1, 0, 2, 2}, {0, 1, 1, 2}};
        int size = 0;
        for (int[] p : passes) {
            int pw = (w - p[0] + p[2] - 1) / p[2], ph = (h - p[1] + p[3] - 1) / p[3];
            if (pw > 0 && ph > 0) size += ph * (1 + pw * 4);
        }
        byte[] png = assemble(chunk("IHDR", ihdr(w, h, 8, 6, 1)),
                chunk("IDAT", deflate(new byte[size], Deflater.DEFAULT_COMPRESSION)),
                chunk("IEND", new byte[0]));
        assertThat(decode(png)).isNotNull(); // independent decoder agrees it is valid
        assertThat(service.validate(new java.io.ByteArrayInputStream(png)).interlaced()).isTrue();
    }

    // ── invalid / hostile input ─────────────────────────────────────────────

    @Test
    void rejectsNonPngDataClaimedAsPng() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};
        assertThat(PngCompressionService.isCandidate(jpeg, "image/png", "photo.png")).isTrue();
        assertThatThrownBy(() -> service.process(jpeg))
                .isInstanceOf(BadRequestException.class)
                .satisfies(t -> assertThat(messageKey(t)).isEqualTo("media.png.invalid"));
    }

    @Test
    void detectsPngByContentNotByLabel() {
        byte[] png = rawRgba(4, 4, transparentGradient(4, 4));
        assertThat(PngCompressionService.isCandidate(png, "application/octet-stream", "upload.bin")).isTrue();
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};
        assertThat(PngCompressionService.isCandidate(jpeg, "image/jpeg", "a.jpg")).isFalse();
    }

    @Test
    void rejectsTruncatedPng() {
        byte[] png = rawRgba(64, 64, photoLike(64, 64, 3));
        byte[] cut = Arrays.copyOf(png, png.length / 2);
        assertThatThrownBy(() -> service.process(cut))
                .satisfies(t -> assertThat(messageKey(t)).isEqualTo("media.png.invalid"));
    }

    @Test
    void rejectsCorruptedChunk() {
        byte[] png = rawRgba(64, 64, photoLike(64, 64, 3));
        png[png.length / 2] ^= 0x5A; // flip bits inside IDAT → CRC mismatch
        assertThatThrownBy(() -> service.process(png))
                .satisfies(t -> assertThat(messageKey(t)).isEqualTo("media.png.invalid"));
    }

    @Test
    void rejectsDecompressionBomb() {
        // IHDR declares 10×10 RGBA (410 raw bytes) but IDAT inflates to 50 MB of zeros.
        byte[] bomb = deflate(new byte[50 * 1024 * 1024], Deflater.BEST_COMPRESSION);
        byte[] png = assemble(chunk("IHDR", ihdr(10, 10, 8, 6, 0)), chunk("IDAT", bomb), chunk("IEND", new byte[0]));
        assertThat(png.length).isLessThan(100_000);
        assertThatThrownBy(() -> service.process(png))
                .satisfies(t -> {
                    assertThat(messageKey(t)).isEqualTo("media.png.invalid");
                    assertThat(((AppException) t).getDetails()).containsEntry("reason", "image data exceeds declared size");
                });
    }

    @Test
    void rejectsOversizedDimensionsBeforeDecoding() {
        byte[] png = assemble(chunk("IHDR", ihdr(30_000, 30_000, 8, 6, 0)),
                chunk("IDAT", deflate(new byte[16], Deflater.DEFAULT_COMPRESSION)), chunk("IEND", new byte[0]));
        assertThatThrownBy(() -> service.process(png))
                .satisfies(t -> assertThat(messageKey(t)).isEqualTo("media.png.dimensions_exceeded"));
    }

    @Test
    void rejectsInvalidBitDepthColourTypeCombination() {
        byte[] png = assemble(chunk("IHDR", ihdr(4, 4, 4, 6, 0)), // RGBA must be 8/16-bit
                chunk("IDAT", deflate(new byte[40], Deflater.DEFAULT_COMPRESSION)), chunk("IEND", new byte[0]));
        assertThatThrownBy(() -> service.process(png))
                .satisfies(t -> assertThat(messageKey(t)).isEqualTo("media.png.invalid"));
    }

    // ── policy ─────────────────────────────────────────────────────────────

    @Test
    void enforcedTargetRejectsStillOversizedPngWithActualSize() {
        requireOptipng();
        PngCompressionService strict = new PngCompressionService(true, TARGET, true, 20_000, 100_000_000L,
                64L * 1024 * 1024, 50_000_000L, 2, 90, 2, "optipng");
        byte[] png = rawRgba(800, 600, photoLike(800, 600, 5));
        assertThatThrownBy(() -> strict.process(png))
                .satisfies(t -> {
                    assertThat(messageKey(t)).isEqualTo("media.png.too_large");
                    assertThat(((AppException) t).getDetails()).containsKey("finalSize").containsEntry("targetBytes", TARGET);
                });
    }

    @Test
    void missingOptimiserStillValidatesAndStoresOriginal() {
        PngCompressionService noBinary = new PngCompressionService(true, TARGET, false, 20_000, 100_000_000L,
                64L * 1024 * 1024, 50_000_000L, 2, 90, 2, "definitely-not-optipng");
        byte[] png = rawRgba(600, 400, transparentGradient(600, 400));
        PngBytesResult r = noBinary.process(png);
        assertThat(r.method()).isEqualTo(Method.ORIGINAL);
        assertThat(r.bytes()).isSameAs(png);
        assertThatThrownBy(() -> noBinary.process(new byte[]{1, 2, 3}))
                .satisfies(t -> assertThat(messageKey(t)).isEqualTo("media.png.invalid"));
    }
}
