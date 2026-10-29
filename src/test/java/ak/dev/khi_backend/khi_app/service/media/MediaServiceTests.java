package ak.dev.khi_backend.khi_app.service.media;

import ak.dev.khi_backend.khi_app.dto.media.MediaDtos.UploadResponse;
import ak.dev.khi_backend.khi_app.service.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaServiceTests {

    @Mock
    private S3Service s3Service;

    @Mock
    private MultipartFile file;

    private MediaService mediaService;

    @BeforeEach
    void setUp() {
        mediaService = new MediaService(s3Service);
    }

    @Test
    void uploadStreamsMultipartFileWithoutReadingItIntoAByteArray() throws Exception {
        long size = 431_176_441L;
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("Bnkay Zhin.mp4");
        when(file.getContentType()).thenReturn("video/mp4");
        when(file.getSize()).thenReturn(size);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(s3Service.uploadStream(
                any(S3Service.InputStreamProvider.class),
                eq(size),
                eq("Bnkay Zhin.mp4"),
                eq("video/mp4"),
                eq("videos")
        )).thenReturn(new S3Service.StoredObject("https://example.test/video.mp4", size, "video/mp4"));

        UploadResponse response = mediaService.upload(file, "video");

        assertThat(response.getFileUrl()).isEqualTo("https://example.test/video.mp4");
        assertThat(response.getFileSize()).isEqualTo(size);
        ArgumentCaptor<S3Service.InputStreamProvider> providerCaptor =
                ArgumentCaptor.forClass(S3Service.InputStreamProvider.class);
        verify(s3Service).uploadStream(
                providerCaptor.capture(),
                eq(size),
                eq("Bnkay Zhin.mp4"),
                eq("video/mp4"),
                eq("videos")
        );
        assertThat(providerCaptor.getValue().open().readAllBytes()).containsExactly(1);
        verify(file, never()).getBytes();
    }
}
