package ak.dev.khi_backend.khi_app.service.media;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** Hand-built PNG fixtures (deliberately unoptimised / malformed on demand). */
final class PngFixtures {

    private PngFixtures() {}

    static byte[] chunk(String type, byte[] data) {
        ByteBuffer b = ByteBuffer.allocate(12 + data.length);
        b.putInt(data.length);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        b.put(t).put(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        b.putInt((int) crc.getValue());
        return b.array();
    }

    static byte[] ihdr(int w, int h, int depth, int colorType, int interlace) {
        return ByteBuffer.allocate(13).putInt(w).putInt(h)
                .put((byte) depth).put((byte) colorType).put((byte) 0).put((byte) 0)
                .put((byte) interlace).array();
    }

    static byte[] deflate(byte[] raw, int level) {
        Deflater d = new Deflater(level);
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return out.toByteArray();
    }

    static byte[] assemble(byte[]... chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        for (byte[] c : chunks) out.writeBytes(c);
        return out.toByteArray();
    }

    /** Unoptimised RGBA8 PNG (filter 0, stored deflate). */
    static byte[] rawRgba(int w, int h, int[] argb, byte[]... extraChunks) {
        byte[] rows = new byte[(w * 4 + 1) * h];
        int o = 0;
        for (int y = 0; y < h; y++) {
            rows[o++] = 0;
            for (int x = 0; x < w; x++) {
                int p = argb[y * w + x];
                rows[o++] = (byte) (p >> 16);
                rows[o++] = (byte) (p >> 8);
                rows[o++] = (byte) p;
                rows[o++] = (byte) (p >>> 24);
            }
        }
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.writeBytes(chunk("IHDR", ihdr(w, h, 8, 6, 0)));
        for (byte[] c : extraChunks) all.writeBytes(c);
        all.writeBytes(chunk("IDAT", deflate(rows, Deflater.NO_COMPRESSION)));
        all.writeBytes(chunk("IEND", new byte[0]));
        return assemble(all.toByteArray());
    }

    /** Gradient with a fully transparent and a semi-transparent band. */
    static int[] transparentGradient(int w, int h) {
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = y < h / 4 ? 0 : y < h / 2 ? 128 : 255;
                px[y * w + x] = (a << 24) | ((x * 255 / w) << 16) | ((y * 255 / h) << 8) | 128;
            }
        }
        return px;
    }

    /** Smooth gradient plus sensor-style noise — photographic content. */
    static int[] photoLike(int w, int h, long seed) {
        Random r = new Random(seed);
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int red = clamp(x * 200 / w + 30 + r.nextInt(25) - 12);
                int green = clamp(y * 180 / h + 40 + r.nextInt(25) - 12);
                int blue = clamp((x + y) * 220 / (w + h) + r.nextInt(25) - 12);
                px[y * w + x] = 0xFF000000 | (red << 16) | (green << 8) | blue;
            }
        }
        return px;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    static byte[] encodeWithImageIo(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    static BufferedImage decode(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }
}
