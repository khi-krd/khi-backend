package ak.dev.khi_backend.khi_app.service.media;

import ak.dev.khi_backend.khi_app.service.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TiptapHtmlProcessor — the single entry-point for ALL media (image, video,
 * audio/voice, document, or any other file) embedded inside Tiptap HTML.
 *
 * <p>The processor scans the inbound HTML for inline base64 data URIs:</p>
 * <ul>
 *   <li>{@code src="data:..."} on {@code <img>}, {@code <video>},
 *       {@code <audio>}, and {@code <source>} tags (images, videos, voice
 *       recordings).</li>
 *   <li>{@code href="data:..."} on {@code <a>} tags (PDFs, documents, any
 *       other downloadable file).</li>
 * </ul>
 *
 * <p>For each match it base64-decodes the payload, uploads the bytes to S3
 * using the MIME-derived folder ({@code images/}, {@code video/},
 * {@code audio/}, or {@code files/}), and rewrites the attribute to point
 * at the resulting public URL. The rewritten HTML is what gets persisted —
 * the database never stores raw binary payloads.</p>
 *
 * <p>Used by About, Contact, Service, News, Project, Sound, Video, Image and
 * Writing — i.e. every module whose description / body is a Tiptap HTML blob.
 * About / Contact / Service have no other media path; everything lives here.</p>
 *
 * <p>Behaviour guarantees:</p>
 * <ul>
 *   <li>Idempotent — HTML that already contains only S3 URLs is returned
 *       unchanged (fast early-out on {@code !html.contains("data:")}).</li>
 *   <li>Null-safe / blank-safe — null and empty strings pass through.</li>
 *   <li>Resilient — a malformed base64 payload or a single failed S3 upload
 *       is logged and the original attribute is left in place; the save
 *       still succeeds for the rest of the document.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TiptapHtmlProcessor {

    private final S3Service s3Service;

    /**
     * Matches {@code src="data:<mime>;base64,<payload>"} (single or double
     * quotes) on {@code <img>}, {@code <video>}, {@code <audio>}, or
     * {@code <source>} — covers IMAGE / VIDEO / AUDIO (voice) inline assets.
     */
    private static final Pattern DATA_URI_SRC = Pattern.compile(
            "src=([\"'])data:([a-zA-Z0-9.+/-]+);base64,([A-Za-z0-9+/=\\s]+?)\\1",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Matches {@code href="data:<mime>;base64,<payload>"} — covers PDFs and
     * any "other file" embedded as a download link by the Tiptap editor.
     */
    private static final Pattern DATA_URI_HREF = Pattern.compile(
            "href=([\"'])data:([a-zA-Z0-9.+/-]+);base64,([A-Za-z0-9+/=\\s]+?)\\1",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Process a Tiptap HTML blob. Returns the same string with every inline
     * base64 data URI on a {@code src} or {@code href} attribute replaced
     * by the S3 public URL of the uploaded asset. Inline assets land in the
     * "content" entity folder since no owning entity is known here.
     */
    public String process(String html) {
        return process(html, "content");
    }

    /**
     * Same as {@link #process(String)} but uploads extracted assets into the
     * owning entity's folder ("<entity>/<media-type>/").
     */
    public String process(String html, String entity) {
        if (html == null || html.isBlank()) return html;
        if (!html.contains("data:")) return html;

        String afterSrc  = rewrite(html,     DATA_URI_SRC,  "src", entity);
        String afterHref = rewrite(afterSrc, DATA_URI_HREF, "href", entity);
        return afterHref;
    }

    private String rewrite(String html, Pattern pattern, String attr, String entity) {
        Matcher m = pattern.matcher(html);
        StringBuilder out = new StringBuilder(html.length());
        int last = 0;
        int uploaded = 0;
        int failed = 0;

        while (m.find()) {
            out.append(html, last, m.start());

            String quote   = m.group(1);
            String mime    = m.group(2).toLowerCase();
            String payload = m.group(3).replaceAll("\\s+", "");

            String replacement;
            try {
                byte[] bytes = Base64.getDecoder().decode(payload);
                String filename  = "tiptap-" + System.nanoTime() + "." + extensionFor(mime);
                String url = s3Service.upload(bytes, filename, mime,
                        s3Service.entityFolder(entity, mime));
                replacement = attr + "=" + quote + url + quote;
                uploaded++;
            } catch (IllegalArgumentException e) {
                log.warn("Tiptap: skipping malformed base64 payload (attr={}, mime={})", attr, mime);
                replacement = m.group(0);
                failed++;
            } catch (Exception e) {
                log.error("Tiptap: failed to upload inline asset (attr={}, mime={})", attr, mime, e);
                replacement = m.group(0);
                failed++;
            }

            out.append(replacement);
            last = m.end();
        }

        if (last == 0) return html;
        out.append(html, last, html.length());

        if (uploaded > 0 || failed > 0) {
            log.info("Tiptap HTML processed: attr={}, uploaded={}, failed={}",
                    attr, uploaded, failed);
        }
        return out.toString();
    }

    private static String extensionFor(String mime) {
        if (mime == null) return "bin";
        return switch (mime) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png"               -> "png";
            case "image/gif"               -> "gif";
            case "image/webp"              -> "webp";
            case "image/svg+xml"           -> "svg";
            case "image/bmp"               -> "bmp";
            case "image/avif"              -> "avif";
            case "video/mp4"               -> "mp4";
            case "video/webm"              -> "webm";
            case "video/quicktime"         -> "mov";
            case "audio/mpeg"              -> "mp3";
            case "audio/mp3"               -> "mp3";
            case "audio/wav"               -> "wav";
            case "audio/x-wav"             -> "wav";
            case "audio/ogg"               -> "ogg";
            case "audio/webm"              -> "weba";
            case "audio/aac", "audio/x-aac"-> "aac";
            case "audio/flac", "audio/x-flac" -> "flac";
            case "audio/mp4", "audio/x-m4a"   -> "m4a";
            case "application/pdf"         -> "pdf";
            case "application/msword"      -> "doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "application/vnd.ms-excel" -> "xls";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx";
            case "application/vnd.ms-powerpoint" -> "ppt";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx";
            case "application/zip"         -> "zip";
            case "application/x-rar-compressed", "application/vnd.rar" -> "rar";
            case "application/x-7z-compressed" -> "7z";
            case "text/plain"              -> "txt";
            case "text/csv"                -> "csv";
            case "application/json"        -> "json";
            default -> {
                int slash = mime.indexOf('/');
                yield slash >= 0 && slash + 1 < mime.length()
                        ? mime.substring(slash + 1)
                        : "bin";
            }
        };
    }
}
