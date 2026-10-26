package ak.dev.khi_backend.khi_app.service.publishment.image;

import ak.dev.khi_backend.khi_app.dto.publishment.image.ImageCollectionDTO.ImageItemDto;
import ak.dev.khi_backend.khi_app.dto.publishment.image.ImageCollectionDTO.Response;
import ak.dev.khi_backend.khi_app.dto.publishment.image.ImageCollectionDTO.UpdateRequest;
import ak.dev.khi_backend.khi_app.enums.publishment.ImageCollectionType;
import ak.dev.khi_backend.khi_app.enums.Language;
import ak.dev.khi_backend.khi_app.model.publishment.image.ImageAlbumItem;
import ak.dev.khi_backend.khi_app.model.publishment.image.ImageCollection;
import ak.dev.khi_backend.khi_app.model.publishment.image.ImageContent;
import ak.dev.khi_backend.khi_app.repository.publishment.image.ImageCollectionLogRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.image.ImageCollectionRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.topic.PublishmentTopicRepository;
import ak.dev.khi_backend.khi_app.service.S3Service;
import ak.dev.khi_backend.khi_app.service.media.MediaMetadataService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageCollectionServiceUpdateTests {

    @Mock private ImageCollectionRepository imageCollectionRepository;
    @Mock private ImageCollectionLogRepository imageCollectionLogRepository;
    @Mock private PublishmentTopicRepository topicRepository;
    @Mock private S3Service s3Service;
    @Mock private TiptapHtmlProcessor tiptapHtmlProcessor;
    @Mock private MediaMetadataService mediaMetadata;

    @InjectMocks
    private ImageCollectionService imageCollectionService;

    @Test
    void existingAlbumItemKeepsSourceAndMetadataWhenUpdateOmitsSource() {
        ImageCollection collection = collectionWithExistingItem();
        when(imageCollectionRepository.findByIdWithGraph(8L))
                .thenReturn(Optional.of(collection));
        when(imageCollectionRepository.save(collection)).thenReturn(collection);

        UpdateRequest request = UpdateRequest.builder()
                .imageAlbum(List.of(ImageItemDto.builder()
                        .id(3L)
                        .captionCkb("updated caption")
                        .sortOrder(0)
                        .build()))
                .build();

        Response response = imageCollectionService.update(
                8L, request, null, null, null, null);

        assertThat(response.getImageAlbum()).singleElement().satisfies(item -> {
            assertThat(item.getId()).isEqualTo(3L);
            assertThat(item.getImageUrl()).isEqualTo("https://cdn.example.com/original.jpg");
            assertThat(item.getFileSizeBytes()).isEqualTo(2048L);
            assertThat(item.getWidthPx()).isEqualTo(1200);
            assertThat(item.getHeightPx()).isEqualTo(800);
            assertThat(item.getCaptionCkb()).isEqualTo("updated caption");
        });
        assertThat(response.getCkbContent().getTitle()).isEqualTo("existing title");
        verify(s3Service, never()).upload(any(byte[].class), any(), any());
    }

    @Test
    void skipsSourcelessAlbumEntriesInsteadOfRejectingThem() {
        ImageCollection collection = collectionWithExistingItem();
        when(imageCollectionRepository.findByIdWithGraph(8L))
                .thenReturn(Optional.of(collection));
        when(imageCollectionRepository.save(collection)).thenReturn(collection);
        when(s3Service.upload(any(byte[].class), any(), any(),
                org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn("https://cdn.example.com/new.jpg");
        when(s3Service.entityFolder(any(), any())).thenReturn("galleries/images");

        UpdateRequest request = UpdateRequest.builder()
                .collectionType(ImageCollectionType.GALLERY)
                .imageAlbum(List.of(
                        ImageItemDto.builder().captionCkb("new upload").build(),
                        ImageItemDto.builder().captionCkb("missing source").build()
                ))
                .build();
        MockMultipartFile upload = new MockMultipartFile(
                "images", "new.jpg", "image/jpeg", new byte[]{1, 2, 3});

        Response response = imageCollectionService.update(
                8L, request, null, null, null, List.of(upload));

        assertThat(response.getImageAlbum()).singleElement().satisfies(item -> {
            assertThat(item.getImageUrl()).isEqualTo("https://cdn.example.com/new.jpg");
            assertThat(item.getCaptionCkb()).isEqualTo("new upload");
        });
        verify(imageCollectionRepository).save(collection);
    }

    @Test
    void treatsUnknownAlbumItemIdAsNewItem() {
        ImageCollection collection = collectionWithExistingItem();
        when(imageCollectionRepository.findByIdWithGraph(8L))
                .thenReturn(Optional.of(collection));
        when(imageCollectionRepository.save(collection)).thenReturn(collection);

        UpdateRequest request = UpdateRequest.builder()
                .imageAlbum(List.of(ImageItemDto.builder()
                        .id(999L)
                        .imageUrl("https://cdn.example.com/fresh.jpg")
                        .build()))
                .build();

        Response response = imageCollectionService.update(
                8L, request, null, null, null, null);

        assertThat(response.getImageAlbum()).singleElement().satisfies(item ->
                assertThat(item.getImageUrl())
                        .isEqualTo("https://cdn.example.com/fresh.jpg"));
        verify(imageCollectionRepository).save(collection);
    }

    @Test
    void acceptsUpdateWithEmptyRequestBody() {
        ImageCollection collection = collectionWithExistingItem();
        when(imageCollectionRepository.findByIdWithGraph(8L))
                .thenReturn(Optional.of(collection));
        when(imageCollectionRepository.save(collection)).thenReturn(collection);

        Response response = imageCollectionService.update(
                8L, null, null, null, null, null);

        assertThat(response.getId()).isEqualTo(8L);
        assertThat(response.getCkbContent().getTitle()).isEqualTo("existing title");
        verify(imageCollectionRepository).save(collection);
    }

    @Test
    void deleteIgnoresMissingCollection() {
        when(imageCollectionRepository.findByIdWithGraph(999L))
                .thenReturn(Optional.empty());

        imageCollectionService.delete(999L);

        verify(imageCollectionRepository, never()).delete(any());
    }

    private ImageCollection collectionWithExistingItem() {
        ImageCollection collection = ImageCollection.builder()
                .id(8L)
                .collectionType(ImageCollectionType.SINGLE)
                .contentLanguages(new java.util.LinkedHashSet<>(List.of(Language.CKB)))
                .ckbContent(ImageContent.builder()
                        .title("existing title")
                        .description("existing description")
                        .build())
                .build();
        ImageAlbumItem item = ImageAlbumItem.builder()
                .id(3L)
                .imageCollection(collection)
                .imageUrl("https://cdn.example.com/original.jpg")
                .fileSizeBytes(2048L)
                .widthPx(1200)
                .heightPx(800)
                .mimeType("image/jpeg")
                .sortOrder(0)
                .build();
        collection.getImageAlbum().add(item);
        return collection;
    }
}
