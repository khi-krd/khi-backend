package ak.dev.khi_backend.khi_app.service.media;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal clamd INSTREAM client — speaks the raw protocol over TCP so no
 * extra dependency is needed. Disabled by default; enabled via
 * {@code app.scan.clamav.*} on deployments that run a clamd container.
 *
 * Protocol: {@code zINSTREAM\0}, then 4-byte big-endian length + bytes per
 * chunk, then a zero-length chunk; clamd replies
 * {@code stream: OK} / {@code stream: <signature> FOUND} / an error line.
 */
@Slf4j
@Component
public class ClamAvScanner {

    private static final int CHUNK_SIZE = 8192;
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private final boolean enabled;
    private final String host;
    private final int port;

    public ClamAvScanner(
            @Value("${app.scan.clamav.enabled:false}") boolean enabled,
            @Value("${app.scan.clamav.host:clamav}") String host,
            @Value("${app.scan.clamav.port:3310}") int port) {
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        if (enabled) {
            log.info("ClamAV scanning enabled → {}:{}", host, port);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Scans a stream.
     *
     * @return {@code null} when the content is clean (or scanning is
     *         disabled); the clamd verdict line when a threat is found.
     * @throws IOException when the scanner cannot be reached or errors —
     *         callers decide whether that rejects the upload (public
     *         donation attachments fail closed).
     */
    public String scan(InputStream in) throws IOException {
        if (!enabled) {
            return null;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            OutputStream out = socket.getOutputStream();
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));

            byte[] buffer = new byte[CHUNK_SIZE];
            ByteBuffer lengthPrefix = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
            int read;
            while ((read = in.read(buffer)) > 0) {
                lengthPrefix.clear();
                lengthPrefix.putInt(read);
                out.write(lengthPrefix.array());
                out.write(buffer, 0, read);
            }
            lengthPrefix.clear();
            lengthPrefix.putInt(0);
            out.write(lengthPrefix.array());
            out.flush();

            String reply = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                    .readLine();
            if (reply == null) {
                throw new IOException("clamd closed the connection without a verdict");
            }
            if (reply.endsWith("OK")) {
                return null;
            }
            if (reply.contains("FOUND")) {
                return reply;
            }
            throw new IOException("clamd scan failed: " + reply);
        }
    }
}
