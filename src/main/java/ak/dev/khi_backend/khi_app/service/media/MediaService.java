package ak.dev.khi_backend.khi_app.service.media;

import ak.dev.khi_backend.khi_app.dto.media.MediaDtos.UploadResponse;
import ak.dev.khi_backend.khi_app.service.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MediaService — shared S3 upload service used by every Tiptap editor across
 * the platform. Mirrors the logic that previously lived in AboutService, but
 * exposed at a neutral endpoint so News / Projects / Services / Sound / Video
 * / Image / Writing can all call it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaService {

    private final S3Service s3Service;

    /**
     * Upload a single file to S3 and return a public URL.
     *
     * @param file the file uploaded as multipart/form-data
     * @param type optional folder hint — an entity name ("about", "news",
     *             "services", "projects", "videos", "sounds", "writings",
     *             "galleries", "contact", "donations", "brandings", "nav",
     *             "site", "featured", "users", …) stores the object under
     *             "<entity>/<media-type>/"; a legacy media-type hint
     *             ("image", "audio", "video", "document") keeps the flat
     *             media-type folder. When null or unknown, the folder is
     *             inferred from the content type.
     */
    public UploadResponse upload(MultipartFile file, String type) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }

        log.info("Uploading media: name={}, hint={}, contentType={}, size={}",
                file.getOriginalFilename(), type,
                file.getContentType(), file.getSize());

        String folder = resolveFolder(type, file.getContentType());

        // Size/type come from what was actually stored — PNGs may have been
        // losslessly optimised server-side.
        S3Service.StoredObject stored = s3Service.uploadStream(file::getInputStream, file.getSize(),
                file.getOriginalFilename(), file.getContentType(), folder);

        log.info("Upload successful: {}", stored.url());

        return UploadResponse.builder()
                .fileUrl(stored.url())
                .fileName(file.getOriginalFilename())
                .fileSize(stored.size())
                .contentType(stored.contentType())
                .build();
    }

    public List<UploadResponse> uploadMultiple(List<MultipartFile> files, String type) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        return files.stream()
                .map(file -> {
                    try {
                        return upload(file, type);
                    } catch (IOException e) {
                        log.error("Failed to upload: {}", file.getOriginalFilename(), e);
                        throw new RuntimeException("Upload failed: " + file.getOriginalFilename(), e);
                    }
                })
                .collect(Collectors.toList());
    }

    public void delete(String fileUrl) {
        if (fileUrl != null && !fileUrl.isBlank()) {
            s3Service.deleteFile(fileUrl);
            log.info("Deleted media from S3: {}", fileUrl);
        }
    }

    private String resolveFolder(String hint, String contentType) {
        if (hint == null || hint.isBlank()) return null;
        String key = hint.toLowerCase().trim();

        String entity = switch (key) {
            case "about", "partner", "partners", "person", "persons",
                 "founder", "team"                                   -> "about";
            case "news"                                              -> "news";
            case "project", "projects"                               -> "projects";
            case "writing", "writings", "book", "books"              -> "writings";
            case "videos", "film", "films", "shortfilm",
                 "shortfilms"                                        -> "videos";
            case "sounds", "sound", "album", "albums"                -> "sounds";
            case "collection", "collections", "galleries"            -> "galleries";
            case "service", "services"                               -> "services";
            case "contact"                                           -> "contact";
            case "donation", "donations"                             -> "donations";
            case "branding", "brandings"                             -> "brandings";
            case "nav", "nav-menu", "menu"                           -> "nav";
            case "site", "site-font", "site-fonts", "font", "fonts"  -> "site";
            case "featured"                                          -> "featured";
            case "user", "users", "profile"                          -> "users";
            case "content", "texts"                                  -> "content";
            default                                                  -> null;
        };
        if (entity != null) {
            return s3Service.entityFolder(entity, contentType);
        }

        // Legacy media-type hints keep the flat folders they always used.
        return switch (key) {
            case "image", "images", "gallery"        -> "images";
            case "video"                             -> "videos";
            case "audio"                             -> "sounds";
            case "document", "documents", "pdf",
                 "file", "files"                    -> "documents";
            default                                  -> null;
        };
    }
}
