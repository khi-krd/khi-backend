package ak.dev.khi_backend.khi_app.service.publishment.writing;

import ak.dev.khi_backend.khi_app.dto.publishment.writing.WritingDtos.LanguageContentDto;
import ak.dev.khi_backend.khi_app.dto.publishment.writing.WritingDtos.Response;
import ak.dev.khi_backend.khi_app.dto.publishment.writing.WritingDtos.UpdateRequest;
import ak.dev.khi_backend.khi_app.model.publishment.writing.Writing;
import ak.dev.khi_backend.khi_app.model.publishment.writing.WritingContent;
import ak.dev.khi_backend.khi_app.repository.publishment.topic.PublishmentTopicRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.writing.WritingLogRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.writing.WritingRepository;
import ak.dev.khi_backend.khi_app.service.S3Service;
import ak.dev.khi_backend.khi_app.service.media.TiptapHtmlProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WritingServiceUpdateTests {

    @Mock private WritingRepository writingRepository;
    @Mock private WritingLogRepository writingLogRepository;
    @Mock private PublishmentTopicRepository topicRepository;
    @Mock private BookGenreService bookGenreService;
    @Mock private S3Service s3Service;
    @Mock private ObjectMapper objectMapper;
    @Mock private TiptapHtmlProcessor tiptapHtmlProcessor;

    @InjectMocks
    private WritingService writingService;

    @Test
    void metadataOnlyUpdateKeepsExistingBookFileSource() {
        when(bookGenreService.resolve(any(), any())).thenReturn(new java.util.LinkedHashSet<>());
        Writing writing = Writing.builder()
                .id(8L)
                .ckbContent(WritingContent.builder()
                        .title("old title")
                        .fileUrl("https://cdn.example.com/original.pdf")
                        .fileSizeBytes(2048L)
                        .pageCount(120)
                        .build())
                .build();
        when(writingRepository.findByIdWithDetails(8L))
                .thenReturn(Optional.of(writing));
        when(writingRepository.save(writing)).thenReturn(writing);

        UpdateRequest request = UpdateRequest.builder()
                .ckbContent(LanguageContentDto.builder()
                        .title("updated title")
                        .build())
                .build();

        Response response = writingService.updateWriting(
                8L, request, null, null, null, null, null);

        assertThat(response.getCkbContent().getTitle()).isEqualTo("updated title");
        assertThat(response.getCkbContent().getFileUrl())
                .isEqualTo("https://cdn.example.com/original.pdf");
        assertThat(response.getCkbContent().getFileSizeBytes()).isEqualTo(2048L);
        assertThat(response.getCkbContent().getPageCount()).isEqualTo(120);
        verify(s3Service, never()).upload(any(byte[].class), any(), any());
    }

    @Test
    void deleteIgnoresMissingWriting() {
        when(writingRepository.findByIdWithDetails(999L))
                .thenReturn(Optional.empty());

        writingService.deleteWriting(999L);

        verify(writingRepository, never()).delete(any());
    }

    @Test
    void deleteDetachesAuditLogsAndChildBooksBeforeHardDelete() {
        Writing child = Writing.builder().id(24L).build();
        Writing writing = Writing.builder()
                .id(23L)
                .seriesId("series-23")
                .seriesBooks(new ArrayList<>())
                .build();
        child.setParentBook(writing);
        writing.getSeriesBooks().add(child);

        when(writingRepository.findByIdWithDetails(23L))
                .thenReturn(Optional.of(writing));
        when(writingRepository.countBySeriesId("series-23")).thenReturn(1L);
        when(writingRepository.findBySeriesIdOrderBySeriesOrderAsc("series-23"))
                .thenReturn(List.of(child));

        writingService.deleteWriting(23L);

        assertThat(child.getParentBook()).isNull();
        var order = inOrder(writingRepository, writingLogRepository);
        order.verify(writingRepository).saveAll(List.of(child));
        order.verify(writingRepository).flush();
        order.verify(writingLogRepository).detachFromWriting(23L);
        order.verify(writingLogRepository).saveAndFlush(any());
        order.verify(writingRepository).delete(writing);
        order.verify(writingRepository).flush();
    }
}
