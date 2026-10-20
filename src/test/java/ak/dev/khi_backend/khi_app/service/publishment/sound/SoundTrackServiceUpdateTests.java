package ak.dev.khi_backend.khi_app.service.publishment.sound;

import ak.dev.khi_backend.khi_app.dto.publishment.sound.SoundTrackDtos.FileCreateRequest;
import ak.dev.khi_backend.khi_app.dto.publishment.sound.SoundTrackDtos.Response;
import ak.dev.khi_backend.khi_app.dto.publishment.sound.SoundTrackDtos.UpdateRequest;
import ak.dev.khi_backend.khi_app.enums.publishment.FileType;
import ak.dev.khi_backend.khi_app.enums.publishment.TrackState;
import ak.dev.khi_backend.khi_app.enums.Language;
import ak.dev.khi_backend.khi_app.exceptions.publishment.sound.SoundTrackValidationException;
import ak.dev.khi_backend.khi_app.model.publishment.sound.SoundTrack;
import ak.dev.khi_backend.khi_app.model.publishment.sound.SoundTrackFile;
import ak.dev.khi_backend.khi_app.model.publishment.sound.SoundTrackContent;
import ak.dev.khi_backend.khi_app.repository.publishment.sound.SoundTrackLogRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.sound.SoundTrackRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.topic.PublishmentTopicRepository;
import ak.dev.khi_backend.khi_app.service.S3Service;
import ak.dev.khi_backend.khi_app.service.media.TiptapHtmlProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SoundTrackServiceUpdateTests {

    @Mock private SoundTrackRepository soundTrackRepository;
    @Mock private SoundTrackLogRepository soundTrackLogRepository;
    @Mock private PublishmentTopicRepository topicRepository;
    @Mock private S3Service s3Service;
    @Mock private TiptapHtmlProcessor tiptapHtmlProcessor;

    @InjectMocks
    private SoundTrackService soundTrackService;

    @Test
    void existingTrackFileKeepsSourceWhenUpdateOnlyChangesMetadata() {
        SoundTrack soundTrack = soundTrackWithExistingFile();
        when(soundTrackRepository.findByIdWithGraph(8L))
                .thenReturn(Optional.of(soundTrack));
        when(soundTrackRepository.save(soundTrack)).thenReturn(soundTrack);

        UpdateRequest request = UpdateRequest.builder()
                .files(List.of(FileCreateRequest.builder()
                        .id(3L)
                        .title("updated title")
                        .fileType(FileType.MP3)
                        .build()))
                .build();

        Response response = soundTrackService.update(
                8L, request, null, null, null, null, null, null, null);

        assertThat(response.getFiles()).singleElement().satisfies(file -> {
            assertThat(file.getId()).isEqualTo(3L);
            assertThat(file.getFileUrl()).isEqualTo("https://cdn.example.com/original.mp3");
            assertThat(file.getSizeBytes()).isEqualTo(2048L);
            assertThat(file.getDurationSeconds()).isEqualTo(180L);
            assertThat(file.getTitle()).isEqualTo("updated title");
        });
        assertThat(response.getCkbContent().getTitle()).isEqualTo("existing title");
        verify(s3Service, never()).upload(any(byte[].class), any(), any());
    }

    @Test
    void validatesAllTrackFilesBeforeUploading() {
        SoundTrack soundTrack = soundTrackWithExistingFile();
        when(soundTrackRepository.findByIdWithGraph(8L))
                .thenReturn(Optional.of(soundTrack));

        UpdateRequest request = UpdateRequest.builder()
                .files(List.of(
                        FileCreateRequest.builder().fileType(FileType.MP3).build(),
                        FileCreateRequest.builder().fileType(FileType.MP3).build()
                ))
                .build();
        MockMultipartFile upload = new MockMultipartFile(
                "audioFiles", "new.mp3", "audio/mpeg", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> soundTrackService.update(
                8L, request, null, null, null,
                List.of(upload), null, null, null))
                .isInstanceOf(SoundTrackValidationException.class)
                .hasMessageContaining("soundTrack.file.source.required");

        verify(s3Service, never()).upload(any(byte[].class), any(), any());
        verify(soundTrackRepository, never()).save(any());
    }

    @Test
    void replacementUploadKeepsUploadedFileSize() throws Exception {
        SoundTrack soundTrack = soundTrackWithExistingFile();
        when(soundTrackRepository.findByIdWithGraph(8L))
                .thenReturn(Optional.of(soundTrack));
        when(soundTrackRepository.save(soundTrack)).thenReturn(soundTrack);
        when(s3Service.upload(any(byte[].class), any(), any()))
                .thenReturn("https://cdn.example.com/replacement.mp3");

        UpdateRequest request = UpdateRequest.builder()
                .files(List.of(FileCreateRequest.builder()
                        .id(3L)
                        .fileType(FileType.MP3)
                        .build()))
                .build();
        byte[] bytes = new byte[]{1, 2, 3, 4};
        MockMultipartFile upload = new MockMultipartFile(
                "audioFiles", "replacement.mp3", "audio/mpeg", bytes);

        Response response = soundTrackService.update(
                8L, request, null, null, null,
                List.of(upload), null, null, null);

        assertThat(response.getFiles()).singleElement().satisfies(file -> {
            assertThat(file.getFileUrl())
                    .isEqualTo("https://cdn.example.com/replacement.mp3");
            assertThat(file.getSizeBytes()).isEqualTo(bytes.length);
        });
    }

    @Test
    void deleteIgnoresMissingSoundTrack() {
        when(soundTrackRepository.findByIdWithGraph(999L))
                .thenReturn(Optional.empty());

        soundTrackService.delete(999L);

        verify(soundTrackRepository, never()).delete(any());
    }

    private SoundTrack soundTrackWithExistingFile() {
        SoundTrack soundTrack = SoundTrack.builder()
                .id(8L)
                .soundType("poem")
                .trackState(TrackState.SINGLE)
                .contentLanguages(new java.util.LinkedHashSet<>(List.of(Language.CKB)))
                .ckbContent(SoundTrackContent.builder()
                        .title("existing title")
                        .description("existing description")
                        .build())
                .build();
        soundTrack.addFile(SoundTrackFile.builder()
                .id(3L)
                .fileUrl("https://cdn.example.com/original.mp3")
                .fileType(FileType.MP3)
                .title("old title")
                .sizeBytes(2048L)
                .durationSeconds(180L)
                .build());
        return soundTrack;
    }
}
