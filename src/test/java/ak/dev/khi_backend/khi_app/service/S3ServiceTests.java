package ak.dev.khi_backend.khi_app.service;

import ak.dev.khi_backend.khi_app.exceptions.BadRequestException;
import ak.dev.khi_backend.khi_app.service.media.MediaProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3ServiceTests {

    @Mock
    private S3Client s3Client;

    @Mock
    private MediaProcessingService mediaProcessing;

    private S3Service s3Service;

    @BeforeEach
    void setUp() throws Exception {
        s3Service = new S3Service(s3Client, mediaProcessing);
        setField("bucket", "my-bucket");
        setField("baseFolder", "khi-web-folders");
        setField("region", "eu-central-1");
    }

    @Test
    void downloadFetchesBytesUsingKeyExtractedFromPublicUrl() {
        byte[] expected = "hello".getBytes();
        when(s3Client.getObjectAsBytes(org.mockito.ArgumentMatchers.any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), expected));

        byte[] actual = s3Service.download("https://my-bucket.s3.eu-central-1.amazonaws.com/khi-web-folders/images/test-file.jpg");

        assertThat(actual).isEqualTo(expected);

        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObjectAsBytes(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("my-bucket");
        assertThat(captor.getValue().key()).isEqualTo("khi-web-folders/images/test-file.jpg");
    }

    @Test
    void downloadRejectsBlankUrl() {
        assertThatThrownBy(() -> s3Service.download("   "))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("s3.download.invalid");
    }

    @Test
    void downloadWrapsS3ErrorsAsBadRequestException() {
        when(s3Client.getObjectAsBytes(org.mockito.ArgumentMatchers.any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());

        assertThatThrownBy(() -> s3Service.download("https://my-bucket.s3.eu-central-1.amazonaws.com/khi-web-folders/files/missing.txt"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("s3.download.failed");
    }

    @Test
    void uploadStreamsContentThroughARepeatableProvider() throws Exception {
        byte[] content = "large-file-content".getBytes();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        String url = s3Service.upload(
                () -> new ByteArrayInputStream(content),
                content.length,
                "film.mp4",
                "video/mp4"
        );

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(requestCaptor.capture(), bodyCaptor.capture());

        assertThat(requestCaptor.getValue().contentLength()).isEqualTo(content.length);
        assertThat(requestCaptor.getValue().contentType()).isEqualTo("video/mp4");
        assertThat(requestCaptor.getValue().key()).contains("/video/");
        assertThat(bodyCaptor.getValue().contentLength()).isEqualTo(content.length);
        assertThat(bodyCaptor.getValue().contentStreamProvider().newStream().readAllBytes())
                .isEqualTo(content);
        assertThat(bodyCaptor.getValue().contentStreamProvider().newStream().readAllBytes())
                .isEqualTo(content);
        assertThat(url).endsWith(".mp4");
    }

    private void setField(String name, Object value) throws Exception {
        Field field = S3Service.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(s3Service, value);
    }
}
