package ak.dev.khi_backend.khi_app.service;

import ak.dev.khi_backend.khi_app.enums.project.ProjectMediaType;
import ak.dev.khi_backend.khi_app.exceptions.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3Service {

    @FunctionalInterface
    public interface InputStreamProvider {
        InputStream open() throws IOException;
    }

    private final S3Client s3Client;

    @Value("${aws.s3.bucket}")
    private String bucket;

    @Value("${aws.s3.base-folder:khi-web-folders}")
    private String baseFolder;

    @Value("${aws.s3.region}")
    private String region;

    @Value("${aws.s3.public-url:}")
    private String publicUrlBase;

    // ============================================================
    // FOLDER NAMES
    // ============================================================
    private static final String FOLDER_IMAGES = "images";
    private static final String FOLDER_VIDEOS = "videos";
    private static final String FOLDER_AUDIO = "sounds";
    private static final String FOLDER_FILES = "documents";
    private static final String FOLDER_ALBUMS = "albums";
    private static final String FOLDER_COVERS = "covers";
    private static final String FOLDER_HOVER = "hover";

    // ============================================================
    // UPLOAD METHODS
    // ============================================================

    /**
     * Upload file with automatic folder detection based on content type
     */
    public String upload(byte[] fileBytes, String originalFilename, String contentType) {
        return upload(fileBytes, originalFilename, contentType, (ProjectMediaType) null);
    }

    /**
     * Upload file with explicit media type (use this when you know the ProjectMediaType)
     */
    public String upload(byte[] fileBytes, String originalFilename, String contentType, ProjectMediaType mediaType) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new BadRequestException("media.invalid", "File is empty or null");
        }

        String folder = mediaType != null ? getFolderForMediaType(mediaType) : detectFolder(contentType);
        String key = generateKey(folder, originalFilename);

        log.info("⬆️ Uploading to S3: bucket={}, folder={}, key={}, contentType={}",
                bucket, folder, key, contentType);

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .build();

            s3Client.putObject(request, RequestBody.fromBytes(fileBytes));

            String publicUrl = getPublicUrl(key);
            log.info("✅ File uploaded successfully: {}", publicUrl);

            return publicUrl;
        } catch (S3Exception e) {
            log.error("❌ S3 upload failed: {}", e.getMessage(), e);
            throw new BadRequestException("s3.upload.failed", "Failed to upload file to S3: " + e.getMessage());
        }
    }

    /**
     * Stream a file to S3 without materializing the whole file in JVM memory.
     * The provider may be called more than once when the AWS client retries.
     */
    public String upload(InputStreamProvider streamProvider, long contentLength,
                         String originalFilename, String contentType) {
        return upload(streamProvider, contentLength, originalFilename, contentType, (String) null);
    }

    /**
     * Stream a file to S3 with an explicit media type.
     */
    public String upload(InputStreamProvider streamProvider, long contentLength,
                         String originalFilename, String contentType, ProjectMediaType mediaType) {
        return upload(streamProvider, contentLength, originalFilename, contentType,
                mediaType != null ? getFolderForMediaType(mediaType) : null);
    }

    /**
     * Stream a file to S3 into an explicit folder under the base folder.
     * When folder is null/blank, it is inferred from the content type.
     */
    public String upload(InputStreamProvider streamProvider, long contentLength,
                         String originalFilename, String contentType, String folder) {
        if (streamProvider == null || contentLength <= 0) {
            throw new BadRequestException("media.invalid", "File is empty or null");
        }

        String resolvedContentType = contentType == null || contentType.isBlank()
                ? "application/octet-stream"
                : contentType;
        String resolvedFolder = normalizeFolder(folder, resolvedContentType);
        String key = generateKey(resolvedFolder, originalFilename);

        log.info("⬆️ Streaming to S3: bucket={}, folder={}, key={}, contentType={}, size={}",
                bucket, resolvedFolder, key, resolvedContentType, contentLength);

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(resolvedContentType)
                    .contentLength(contentLength)
                    .build();

            RequestBody body = RequestBody.fromContentProvider(
                    () -> {
                        try {
                            return streamProvider.open();
                        } catch (IOException e) {
                            throw new UncheckedIOException("Failed to open upload stream", e);
                        }
                    },
                    contentLength,
                    resolvedContentType
            );
            s3Client.putObject(request, body);

            String publicUrl = getPublicUrl(key);
            log.info("✅ File uploaded successfully: {}", publicUrl);
            return publicUrl;
        } catch (UncheckedIOException e) {
            log.error("❌ Could not read file for S3 upload: {}", e.getMessage(), e);
            throw new BadRequestException("s3.upload.failed",
                    "Failed to read uploaded file: " + e.getMessage());
        } catch (S3Exception | SdkClientException e) {
            log.error("❌ S3 streaming upload failed: {}", e.getMessage(), e);
            throw new BadRequestException("s3.upload.failed",
                    "Failed to upload file to S3: " + e.getMessage());
        }
    }

    /**
     * ✅ Upload album cover image (CKB or KMR)
     */
    public String uploadAlbumCover(byte[] fileBytes, String originalFilename, String contentType, boolean isCkb) {
        String subFolder = isCkb ? "ckb" : "kmr";
        String key = baseFolder + "/" + FOLDER_ALBUMS + "/" + FOLDER_COVERS + "/" + subFolder + "/" +
                UUID.randomUUID() + "-" + sanitizeFilename(originalFilename);

        log.info("⬆️ Uploading album cover ({}): {}", isCkb ? "CKB" : "KMR", key);

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .build();

            s3Client.putObject(request, RequestBody.fromBytes(fileBytes));

            String publicUrl = getPublicUrl(key);
            log.info("✅ Album cover uploaded: {}", publicUrl);
            return publicUrl;
        } catch (S3Exception e) {
            log.error("❌ Failed to upload album cover: {}", e.getMessage(), e);
            throw new BadRequestException("s3.upload.failed", "Failed to upload cover: " + e.getMessage());
        }
    }

    /**
     * ✅ Upload album hover image
     */
    public String uploadAlbumHover(byte[] fileBytes, String originalFilename, String contentType) {
        String key = baseFolder + "/" + FOLDER_ALBUMS + "/" + FOLDER_HOVER + "/" +
                UUID.randomUUID() + "-" + sanitizeFilename(originalFilename);

        log.info("⬆️ Uploading album hover image: {}", key);

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .build();

            s3Client.putObject(request, RequestBody.fromBytes(fileBytes));

            String publicUrl = getPublicUrl(key);
            log.info("✅ Album hover image uploaded: {}", publicUrl);
            return publicUrl;
        } catch (S3Exception e) {
            log.error("❌ Failed to upload hover image: {}", e.getMessage(), e);
            throw new BadRequestException("s3.upload.failed", "Failed to upload hover: " + e.getMessage());
        }
    }

    // ============================================================
    // DELETE METHODS
    // ============================================================

    /**
     * Download file bytes from S3 by full URL.
     */
    public byte[] download(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            throw new BadRequestException("s3.download.invalid", "File URL is required");
        }

        String key = extractKeyFromUrl(fileUrl);
        if (key == null || key.isBlank()) {
            throw new BadRequestException("s3.download.invalid", "Could not extract S3 key from URL: " + fileUrl);
        }

        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();

            ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(request);
            byte[] bytes = objectBytes.asByteArray();

            log.info("⬇️ Downloaded from S3: bucket={}, key={}, size={} bytes", bucket, key, bytes.length);
            return bytes;
        } catch (S3Exception e) {
            log.error("❌ S3 download failed: bucket={}, key={}, error={}", bucket, key, e.getMessage(), e);
            throw new BadRequestException("s3.download.failed", "Failed to download file from S3: " + e.getMessage());
        }
    }

    /**
     * ✅ Delete file from S3 by full URL
     */
    public void deleteFile(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            log.warn("⚠️ Delete requested with null/blank URL");
            return;
        }

        try {
            String key = extractKeyFromUrl(fileUrl);
            if (key == null) {
                log.warn("⚠️ Could not extract key from URL: {}", fileUrl);
                return;
            }

            deleteByKey(key);
        } catch (Exception e) {
            log.error("❌ Failed to delete file: {}", fileUrl, e);
            // Don't throw - deletion failure shouldn't break business logic
        }
    }

    /**
     * ✅ Delete file from S3 by key
     */
    public void deleteByKey(String key) {
        if (key == null || key.isBlank()) {
            log.warn("⚠️ Delete requested with null/blank key");
            return;
        }

        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();

            s3Client.deleteObject(request);
            log.info("🗑️ Deleted from S3: bucket={}, key={}", bucket, key);
        } catch (S3Exception e) {
            log.error("❌ S3 delete failed: bucket={}, key={}, error={}", bucket, key, e.getMessage());
            // Don't throw - allow cascade to continue
        }
    }

    /**
     * ✅ Delete multiple files at once
     */
    public void deleteFiles(java.util.List<String> fileUrls) {
        if (fileUrls == null || fileUrls.isEmpty()) return;

        log.info("🗑️ Batch deleting {} files from S3", fileUrls.size());

        for (String url : fileUrls) {
            deleteFile(url);
        }
    }

    // ============================================================
    // URL & KEY HELPERS
    // ============================================================

    /**
     * Extract S3 key from various URL formats
     */
    public String extractKeyFromUrl(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) return null;

        try {
            URI uri = new URI(fileUrl);
            String path = uri.getPath();

            // Remove leading slash
            if (path.startsWith("/")) {
                path = path.substring(1);
            }

            // Handle virtual-hosted style: bucket.s3.region.amazonaws.com/key
            // Handle path-style: s3.region.amazonaws.com/bucket/key

            // If path starts with bucket name, remove it
            if (path.startsWith(bucket + "/")) {
                path = path.substring(bucket.length() + 1);
            }

            // If path starts with baseFolder, keep it (it's part of the key)
            // Key format: khi-web-folders/images/uuid-filename.jpg

            log.debug("Extracted key from URL: {} -> {}", fileUrl, path);
            return path;

        } catch (Exception e) {
            log.warn("⚠️ Failed to parse S3 URL: {}", fileUrl, e);

            // Fallback: try simple string manipulation
            return extractKeyFallback(fileUrl);
        }
    }

    /**
     * Fallback key extraction for non-standard URLs
     */
    private String extractKeyFallback(String fileUrl) {
        // Try to find baseFolder in URL and extract from there
        int baseIndex = fileUrl.indexOf(baseFolder);
        if (baseIndex != -1) {
            String key = fileUrl.substring(baseIndex);
            // Remove query parameters if any
            int queryIndex = key.indexOf("?");
            if (queryIndex != -1) {
                key = key.substring(0, queryIndex);
            }
            return key;
        }

        // Last resort: return everything after the last slash
        int lastSlash = fileUrl.lastIndexOf('/');
        if (lastSlash != -1 && lastSlash < fileUrl.length() - 1) {
            return baseFolder + "/" + FOLDER_FILES + "/" + fileUrl.substring(lastSlash + 1);
        }

        return null;
    }

    /**
     * Get public URL for a key
     */
    public String getPublicUrl(String key) {
        if (publicUrlBase != null && !publicUrlBase.isBlank()) {
            return publicUrlBase.replaceAll("/+$", "") + "/" + key;
        }
        return "https://" + bucket + ".s3." + region + ".amazonaws.com/" + key;
    }

    /**
     * Check if URL is from our S3 bucket
     */
    public boolean isOurS3Url(String url) {
        if (url == null) return false;
        if (publicUrlBase != null && !publicUrlBase.isBlank()
                && url.startsWith(publicUrlBase.replaceAll("/+$", ""))) {
            return true;
        }
        return url.contains(bucket) && url.contains(".s3.");
    }

    // ============================================================
    // FOLDER DETECTION
    // ============================================================

    private String detectFolder(String contentType) {
        if (contentType == null) return FOLDER_FILES;

        String type = contentType.toLowerCase();

        if (type.startsWith("image/")) return FOLDER_IMAGES;
        if (type.startsWith("video/")) return FOLDER_VIDEOS;
        if (type.startsWith("audio/")) return FOLDER_AUDIO;

        return FOLDER_FILES;
    }

    private String normalizeFolder(String folder, String contentType) {
        if (folder == null || folder.isBlank()) {
            return detectFolder(contentType);
        }
        String cleaned = folder.trim().replaceAll("^/+", "").replaceAll("/+$", "");
        return cleaned.isBlank() ? detectFolder(contentType) : cleaned;
    }

    private String getFolderForMediaType(ProjectMediaType mediaType) {
        if (mediaType == null) return FOLDER_FILES;

        return switch (mediaType) {
            case IMAGE -> FOLDER_IMAGES;
            case VIDEO -> FOLDER_VIDEOS;
            case AUDIO -> FOLDER_AUDIO;
            case DOCUMENT, PDF, TEXT -> FOLDER_FILES;
            default -> FOLDER_FILES;
        };
    }

    // ============================================================
    // KEY GENERATION
    // ============================================================

    private String generateKey(String folder, String filename) {
        String sanitized = sanitizeFilename(filename);
        return baseFolder + "/" + folder + "/" + UUID.randomUUID() + "-" + sanitized;
    }

    private String sanitizeFilename(String filename) {
        if (filename == null) return "file";
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
