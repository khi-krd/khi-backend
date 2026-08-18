package ak.dev.khi_backend.khi_app.service.publishment.video;

import ak.dev.khi_backend.khi_app.exceptions.AppException;
import ak.dev.khi_backend.khi_app.model.publishment.video.FilmReklamVideo;
import ak.dev.khi_backend.khi_app.repository.publishment.topic.PublishmentTopicRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.video.FilmReklamVideoRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.video.VideoLogRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.video.VideoRepository;
import ak.dev.khi_backend.khi_app.service.S3Service;
import ak.dev.khi_backend.khi_app.service.media.TiptapHtmlProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The homepage Film section background video — a singleton.
 *
 * <p>The rules the website and the dashboard both depend on: one row site-wide, a
 * missing video is a 404 rather than an empty payload, and a failed replace never
 * leaves the site with no background.</p>
 */
@ExtendWith(MockitoExtension.class)
class VideoServiceFilmReklamVideoTests {

    private static final String OLD_URL = "https://s3-khiwebsite.s3.../video/old-bg.mp4";
    private static final String NEW_URL = "https://s3-khiwebsite.s3.../video/film-bg.mp4";

    @Mock private VideoRepository videoRepository;
    @Mock private VideoLogRepository videoLogRepository;
    @Mock private PublishmentTopicRepository topicRepository;
    @Mock private FilmReklamVideoRepository filmReklamVideoRepository;
    @Mock private S3Service s3Service;
    @Mock private TiptapHtmlProcessor tiptapHtmlProcessor;

    @InjectMocks
    private VideoService videoService;

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    void theFirstUploadIsStoredWithItsMetadata() {
        when(filmReklamVideoRepository.findTopByOrderByIdAsc()).thenReturn(Optional.empty());
        when(s3Service.upload(any(byte[].class), anyString(), anyString())).thenReturn(NEW_URL);
        when(filmReklamVideoRepository.save(any(FilmReklamVideo.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = videoService.createFilmReklamVideo(mp4("film-bg.mp4", 7199));

        assertThat(result.getVideoUrl()).isEqualTo(NEW_URL);
        assertThat(result.getSizeBytes()).isEqualTo(7199);
        assertThat(result.getMimeType()).isEqualTo("video/mp4");
    }

    @Test
    void aSecondUploadIsRejectedRatherThanCreatingATwinRow() {
        when(filmReklamVideoRepository.findTopByOrderByIdAsc())
                .thenReturn(Optional.of(stored()));

        assertThatThrownBy(() -> videoService.createFilmReklamVideo(mp4("second.mp4", 100)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getMessageKey())
                .isEqualTo("video.reklamVideo.already_exists");

        verify(filmReklamVideoRepository, never()).save(any());
    }

    @Test
    void aNonVideoFileIsRejectedBeforeAnythingReachesS3() {
        var poster = new MockMultipartFile(
                "videoFile", "poster.jpg", "image/jpeg", new byte[] {1, 2, 3});

        assertThatThrownBy(() -> videoService.createFilmReklamVideo(poster))
                .isInstanceOf(AppException.class);

        verify(s3Service, never()).upload(any(byte[].class), anyString(), anyString());
        verify(filmReklamVideoRepository, never()).save(any());
    }

    @Test
    void anEmptyPartIsRejected() {
        var empty = new MockMultipartFile("videoFile", "x.mp4", "video/mp4", new byte[0]);

        assertThatThrownBy(() -> videoService.createFilmReklamVideo(empty))
                .isInstanceOf(AppException.class);

        verify(s3Service, never()).upload(any(byte[].class), anyString(), anyString());
    }

    // ── read ─────────────────────────────────────────────────────────────────

    @Test
    void readingBeforeAnythingIsUploadedIs404NotAnEmptyPayload() {
        when(filmReklamVideoRepository.findTopByOrderByIdAsc()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> videoService.getFilmReklamVideo())
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    AppException app = (AppException) e;
                    assertThat(app.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(app.getMessageKey()).isEqualTo("video.reklamVideo.not_found");
                });
    }

    // ── replace ──────────────────────────────────────────────────────────────

    @Test
    void replacingSwapsTheFileAndDropsTheOldS3Object() {
        FilmReklamVideo existing = stored();
        when(filmReklamVideoRepository.findTopByOrderByIdAsc()).thenReturn(Optional.of(existing));
        when(s3Service.upload(any(byte[].class), anyString(), anyString())).thenReturn(NEW_URL);
        when(filmReklamVideoRepository.save(any(FilmReklamVideo.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = videoService.updateFilmReklamVideo(mp4("film-bg.mp4", 7199));

        assertThat(result.getVideoUrl()).isEqualTo(NEW_URL);
        assertThat(result.getSizeBytes()).isEqualTo(7199);
        verify(s3Service).deleteFile(OLD_URL);
    }

    @Test
    void aFailedUploadLeavesTheExistingVideoUntouched() {
        FilmReklamVideo existing = stored();
        when(filmReklamVideoRepository.findTopByOrderByIdAsc()).thenReturn(Optional.of(existing));
        when(s3Service.upload(any(byte[].class), anyString(), anyString()))
                .thenThrow(new RuntimeException("S3 is down"));

        assertThatThrownBy(() -> videoService.updateFilmReklamVideo(mp4("film-bg.mp4", 10)))
                .isInstanceOf(RuntimeException.class);

        // The old object must survive — otherwise the section loses its background
        // and there is nothing to fall back to.
        verify(s3Service, never()).deleteFile(anyString());
        verify(filmReklamVideoRepository, never()).save(any());
    }

    @Test
    void replacingBeforeAnythingExistsIs404() {
        when(filmReklamVideoRepository.findTopByOrderByIdAsc()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> videoService.updateFilmReklamVideo(mp4("film-bg.mp4", 10)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getMessageKey())
                .isEqualTo("video.reklamVideo.not_found");
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    void deletingRemovesTheRowAndTheS3Object() {
        FilmReklamVideo existing = stored();
        when(filmReklamVideoRepository.findTopByOrderByIdAsc()).thenReturn(Optional.of(existing));

        videoService.deleteFilmReklamVideo();

        verify(filmReklamVideoRepository).delete(existing);
        verify(s3Service).deleteFile(OLD_URL);
    }

    @Test
    void deletingTwiceIs404TheSecondTime() {
        when(filmReklamVideoRepository.findTopByOrderByIdAsc()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> videoService.deleteFilmReklamVideo())
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getMessageKey())
                .isEqualTo("video.reklamVideo.not_found");

        verify(s3Service, never()).deleteFile(anyString());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private FilmReklamVideo stored() {
        return FilmReklamVideo.builder()
                .id(1L)
                .videoUrl(OLD_URL)
                .sizeBytes(5_000_000L)
                .mimeType("video/mp4")
                .build();
    }

    private MockMultipartFile mp4(String name, int sizeBytes) {
        return new MockMultipartFile("videoFile", name, "video/mp4", new byte[sizeBytes]);
    }
}
