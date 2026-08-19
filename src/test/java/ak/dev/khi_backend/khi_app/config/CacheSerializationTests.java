package ak.dev.khi_backend.khi_app.config;

import ak.dev.khi_backend.khi_app.dto.news.NewsDto;
import ak.dev.khi_backend.khi_app.dto.project.ProjectResponse;
import ak.dev.khi_backend.khi_app.dto.publishment.image.ImageCollectionDTO;
import ak.dev.khi_backend.khi_app.dto.publishment.sound.SoundTrackDtos;
import ak.dev.khi_backend.khi_app.dto.service.ServiceDTOs;
import ak.dev.khi_backend.khi_app.enums.Language;
import ak.dev.khi_backend.khi_app.enums.MediaKind;
import ak.dev.khi_backend.khi_app.enums.project.ProjectStatus;
import ak.dev.khi_backend.khi_app.enums.publishment.AttachmentType;
import ak.dev.khi_backend.khi_app.enums.publishment.AudioChannel;
import ak.dev.khi_backend.khi_app.enums.publishment.FileType;
import ak.dev.khi_backend.khi_app.enums.publishment.ImageCollectionType;
import ak.dev.khi_backend.khi_app.enums.publishment.TrackState;
import ak.dev.khi_backend.khi_app.model.media.MediaItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the serialization contract that {@link CacheConfig} depends on.
 *
 * Spring Boot's Redis cache serializes values with JDK serialization, so every type
 * reachable from a {@code @Cacheable} method's return value must be Serializable.
 * A non-Serializable field added to any of these DTO graphs would otherwise fail only
 * at runtime, on the first cache write, in whichever environment has Redis attached.
 *
 * Each test builds a deliberately fully-populated graph — every nested DTO, every
 * collection, every enum — and round-trips the {@code PageImpl} that the service layer
 * actually caches. {@code writeObject} throwing is the real assertion; reading back and
 * checking a deep field proves the round trip, not just the write.
 *
 * No Spring context, no Redis, no database — this runs in milliseconds on every build.
 */
class CacheSerializationTests {

    // ─────────────────────────────────────────────────────────────────────────
    // The five cached page shapes
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Page<NewsDto> survives JDK serialization — NewsService caches 5 methods")
    void newsPageIsSerializable() throws Exception {
        NewsDto news = NewsDto.builder()
                .id(7L)
                .coverUrl("https://cdn/khi/news-7.jpg")
                .coverMediaType(MediaKind.IMAGE)
                .coverThumbnailUrl("https://cdn/khi/news-7-thumb.jpg")
                .featureImageUrl("https://cdn/khi/news-7-hero.jpg")
                .mediaGallery(List.of(mediaItem()))
                .datePublished(LocalDate.of(2026, 4, 2))
                .createdAt(LocalDateTime.of(2026, 4, 2, 18, 31, 7))
                .updatedAt(LocalDateTime.of(2026, 4, 3, 9, 0, 0))
                .contentLanguages(new LinkedHashSet<>(List.of(Language.CKB, Language.KMR)))
                .category(NewsDto.CategoryDto.builder().ckbName("کولتوور").kmrName("Çand").build())
                .subCategory(NewsDto.SubCategoryDto.builder().ckbName("مۆسیقا").kmrName("Muzîk").build())
                .ckbContent(NewsDto.LanguageContentDto.builder()
                        .title("هەواڵی کوردستان").description("<p>ناوەڕۆک</p>").build())
                .kmrContent(NewsDto.LanguageContentDto.builder()
                        .title("Nûçeya Kurdistanê").description("<p>Naverok</p>").build())
                .tags(bilingualSet())
                .keywords(bilingualSet())
                .build();

        Page<NewsDto> restored = roundTrip(page(news));

        assertThat(restored.getTotalElements()).isEqualTo(1);
        NewsDto out = restored.getContent().get(0);
        assertThat(out.getCkbContent().getTitle()).isEqualTo("هەواڵی کوردستان");
        assertThat(out.getCategory().getKmrName()).isEqualTo("Çand");
        assertThat(out.getTags().getCkb()).containsExactly("تاگ");
        assertThat(out.getMediaGallery().get(0).getKind()).isEqualTo(MediaKind.AUDIO);
        assertThat(out.getContentLanguages()).containsExactly(Language.CKB, Language.KMR);
    }

    @Test
    @DisplayName("Page<ProjectResponse> survives JDK serialization — ProjectService caches 4 methods")
    void projectPageIsSerializable() throws Exception {
        ProjectResponse project = ProjectResponse.builder()
                .id(41L)
                .coverUrl("https://cdn/khi/p41.jpg")
                .coverMediaType(MediaKind.VIDEO)
                .coverThumbnailUrl("https://cdn/khi/p41-poster.jpg")
                .featureImageUrl("https://cdn/khi/p41-hero.jpg")
                .mediaGallery(List.of(mediaItem()))
                .projectTypeCkb("لێکۆڵینەوە")
                .projectTypeKmr("Lêkolîn")
                .status(ProjectStatus.ONGOING)
                .projectDate(LocalDate.of(2026, 1, 15))
                .contentLanguages(new LinkedHashSet<>(List.of(Language.CKB)))
                .ckbContent(ProjectResponse.ProjectContentBlockDto.builder()
                        .title("پرۆژەی کوردستان").description("<p>وەسف</p>").location("هەولێر").build())
                .kmrContent(ProjectResponse.ProjectContentBlockDto.builder()
                        .title("Projeya Kurdistanê").description("<p>Danasîn</p>").location("Hewlêr").build())
                .tagsCkb(List.of("تاگ"))
                .tagsKmr(List.of("etîket"))
                .keywordsCkb(List.of("کلیل"))
                .keywordsKmr(List.of("bêje"))
                .createdAt(Instant.parse("2026-01-15T10:00:00Z"))
                .updatedAt(Instant.parse("2026-02-01T10:00:00Z"))
                .createdBy("akar")
                .updatedBy("akar")
                .build();

        Page<ProjectResponse> restored = roundTrip(page(project));

        ProjectResponse out = restored.getContent().get(0);
        assertThat(out.getCkbContent().getLocation()).isEqualTo("هەولێر");
        assertThat(out.getStatus()).isEqualTo(ProjectStatus.ONGOING);
        assertThat(out.getCreatedAt()).isEqualTo(Instant.parse("2026-01-15T10:00:00Z"));
        assertThat(out.getMediaGallery()).hasSize(1);
    }

    @Test
    @DisplayName("Page<ServiceResponse> survives JDK serialization — ServiceService caches 3 methods")
    void servicePageIsSerializable() throws Exception {
        ServiceDTOs.ServiceResponse service = ServiceDTOs.ServiceResponse.builder()
                .id(3L)
                .serviceType("Training")
                .location("Erbil")
                .active(true)
                .publishedAt("2026-03-01T08:00:00")
                .sortOrder(2)
                .layoutType("HERO")
                .heroVideoUrl("https://cdn/khi/hero.mp4")
                .heroPosterUrl("https://cdn/khi/hero.jpg")
                .navAnchorId("training")
                .galleryMedia(List.of(ServiceDTOs.MediaItem.builder()
                        .type("VIDEO")
                        .url("https://cdn/khi/service-hero.mp4")
                        .posterUrl("https://cdn/khi/service-hero.jpg")
                        .alt("ڕاهێنان")
                        .build()))
                .featureImageUrls(List.of("https://cdn/khi/f1.jpg"))
                .thumbnailUrls(List.of("https://cdn/khi/t1.jpg"))
                .partnerIds(List.of(11L, 12L))
                .contents(List.of(ServiceDTOs.ServiceContentResponse.builder()
                        .id(9L)
                        .languageCode("ckb")
                        .title("ڕاهێنان")
                        .description("<p>وەسف</p>")
                        .featureDescription("کورتە")
                        .build()))
                .featured(true)
                .featuredOrder(1)
                .featureImageUrl("https://cdn/khi/feat.jpg")
                .createdAt("2026-03-01T08:00:00")
                .updatedAt("2026-03-02T08:00:00")
                .build();

        Page<ServiceDTOs.ServiceResponse> restored = roundTrip(page(service));

        ServiceDTOs.ServiceResponse out = restored.getContent().get(0);
        assertThat(out.getContents().get(0).getTitle()).isEqualTo("ڕاهێنان");
        assertThat(out.getPartnerIds()).containsExactly(11L, 12L);
        assertThat(out.isFeatured()).isTrue();
        // ServiceDTOs has its OWN nested MediaItem, distinct from model.media.MediaItem
        assertThat(out.getGalleryMedia().get(0).getPosterUrl()).isEqualTo("https://cdn/khi/service-hero.jpg");
    }

    @Test
    @DisplayName("Page<SoundTrackDtos.Response> survives JDK serialization — SoundTrackService caches 6 methods")
    void soundTrackPageIsSerializable() throws Exception {
        SoundTrackDtos.Response track = SoundTrackDtos.Response.builder()
                .id(5L)
                .ckbCoverUrl("https://cdn/khi/s5-ckb.jpg")
                .kmrCoverUrl("https://cdn/khi/s5-kmr.jpg")
                .hoverCoverUrl("https://cdn/khi/s5-hover.jpg")
                .featureImageUrl("https://cdn/khi/s5-hero.jpg")
                .soundType("poem")
                .trackState(TrackState.MULTI)
                .albumOfMemories(Boolean.TRUE)
                .topicId(2L)
                .topicNameCkb("شیعر")
                .topicNameKmr("Helbest")
                .contentLanguages(new LinkedHashSet<>(List.of(Language.CKB, Language.KMR)))
                .ckbContent(SoundTrackDtos.LanguageContentDto.builder()
                        .title("هاوار").description("وەسف").build())
                .kmrContent(SoundTrackDtos.LanguageContentDto.builder()
                        .title("Hawar").description("Danasîn").build())
                .locations(new LinkedHashSet<>(List.of("هەولێر")))
                .reader("خوێنەر")
                .directors(new LinkedHashSet<>(List.of("دەرهێنەر")))
                .terms("مەرجەکان")
                .thisProjectOfInstitute(Boolean.TRUE)
                .tags(soundBilingualSet())
                .keywords(soundBilingualSet())
                .files(List.of(SoundTrackDtos.FileResponse.builder()
                        .id(31L)
                        .fileUrl("https://cdn/khi/s5.mp3")
                        .externalUrl("https://example.org/s5")
                        .embedUrl("https://example.org/embed/s5")
                        .title("تراک ١")
                        .fileType(FileType.AUDIO)
                        .publishmentYear(2026)
                        .sizeBytes(4_200_000L)
                        .durationSeconds(214L)
                        .durationMinutes(3.57)
                        .bitRate("320kbps")
                        .sampleRate("44100")
                        .audioChannel(AudioChannel.STEREO)
                        .form("فۆرم")
                        .genre("ژانر")
                        .recordingVenue("ستۆدیۆ")
                        .brochures(List.of(SoundTrackDtos.BrochureResponse.builder()
                                .id(77L)
                                .imageUrl("https://cdn/khi/b77.jpg")
                                .caption("بروشور")
                                .brochureOrder(0)
                                .build()))
                        .build()))
                .totalDurationSeconds(214L)
                .totalSizeBytes(4_200_000L)
                .albumName("ئەلبوم")
                .publishmentYear(2026)
                .cdNumber(1)
                .totalTracks(12)
                .attachments(List.of(SoundTrackDtos.AttachmentResponse.builder()
                        .id(88L)
                        .fileUrl("https://cdn/khi/a88.pdf")
                        .title("پەیوەست")
                        .attachmentType(AttachmentType.PDF)
                        .sizeBytes(120_000L)
                        .mimeType("application/pdf")
                        .attachmentOrder(0)
                        .build()))
                .createdAt(LocalDateTime.of(2026, 5, 1, 12, 0))
                .updatedAt(LocalDateTime.of(2026, 5, 2, 12, 0))
                .build();

        Page<SoundTrackDtos.Response> restored = roundTrip(page(track));

        SoundTrackDtos.Response out = restored.getContent().get(0);
        assertThat(out.getCkbContent().getTitle()).isEqualTo("هاوار");
        assertThat(out.getTrackState()).isEqualTo(TrackState.MULTI);
        assertThat(out.getFiles().get(0).getAudioChannel()).isEqualTo(AudioChannel.STEREO);
        assertThat(out.getFiles().get(0).getBrochures().get(0).getCaption()).isEqualTo("بروشور");
        assertThat(out.getAttachments().get(0).getAttachmentType()).isEqualTo(AttachmentType.PDF);
        assertThat(out.getDirectors()).containsExactly("دەرهێنەر");
    }

    @Test
    @DisplayName("Page<ImageCollectionDTO.Response> survives JDK serialization — ImageCollectionService caches 5 methods")
    void imageCollectionPageIsSerializable() throws Exception {
        ImageCollectionDTO.Response collection = ImageCollectionDTO.Response.builder()
                .id(12L)
                .slugCkb("کۆمەڵە-وێنە")
                .slugKmr("koma-wene")
                .collectionType(ImageCollectionType.PHOTO_STORY)
                .ckbCoverUrl("https://cdn/khi/i12-ckb.jpg")
                .kmrCoverUrl("https://cdn/khi/i12-kmr.jpg")
                .hoverCoverUrl("https://cdn/khi/i12-hover.jpg")
                .featureImageUrl("https://cdn/khi/i12-hero.jpg")
                .topicId(4L)
                .topicNameCkb("مێژوو")
                .topicNameKmr("Dîrok")
                .publishmentDate(LocalDate.of(2026, 6, 10))
                .contentLanguages(new LinkedHashSet<>(List.of(Language.CKB)))
                .ckbContent(ImageCollectionDTO.LanguageContentDto.builder()
                        .title("گەلەری").description("<p>وەسف</p>").build())
                .kmrContent(ImageCollectionDTO.LanguageContentDto.builder()
                        .title("Gelerî").description("<p>Danasîn</p>").build())
                .tags(imageBilingualSet())
                .keywords(imageBilingualSet())
                .imageAlbum(List.of(ImageCollectionDTO.ImageItemDto.builder()
                        .id(101L)
                        .imageUrl("https://cdn/khi/i12-1.jpg")
                        .externalUrl("https://example.org/i12-1")
                        .embedUrl("https://example.org/embed/i12-1")
                        .captionCkb("ژێرنووس")
                        .captionKmr("Jêrnivîs")
                        .descriptionCkb("وەسف")
                        .descriptionKmr("Danasîn")
                        .sortOrder(0)
                        .fileSizeBytes(890_000L)
                        .widthPx(1920)
                        .heightPx(1080)
                        .mimeType("image/jpeg")
                        .build()))
                .createdAt(LocalDateTime.of(2026, 6, 10, 9, 0))
                .updatedAt(LocalDateTime.of(2026, 6, 11, 9, 0))
                .build();

        Page<ImageCollectionDTO.Response> restored = roundTrip(page(collection));

        ImageCollectionDTO.Response out = restored.getContent().get(0);
        assertThat(out.getCollectionType()).isEqualTo(ImageCollectionType.PHOTO_STORY);
        assertThat(out.getCkbContent().getTitle()).isEqualTo("گەلەری");
        assertThat(out.getImageAlbum().get(0).getWidthPx()).isEqualTo(1920);
        assertThat(out.getImageAlbum().get(0).getCaptionKmr()).isEqualTo("Jêrnivîs");
    }

    @Test
    @DisplayName("An empty page caches too — searches that match nothing are cached like any other result")
    void emptyPageIsSerializable() throws Exception {
        Page<NewsDto> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);

        Page<NewsDto> restored = roundTrip(empty);

        assertThat(restored.getContent()).isEmpty();
        assertThat(restored.getTotalElements()).isZero();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Wraps one item in the same PageImpl shape the service layer returns and caches. */
    private static <T> Page<T> page(T item) {
        return new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1);
    }

    /**
     * The assertion that matters: writeObject throws NotSerializableException the moment
     * any reachable field is not Serializable. Reading back proves the round trip.
     */
    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }

    private static MediaItem mediaItem() {
        return MediaItem.builder()
                .url("https://cdn/khi/clip.mp3")
                .kind(MediaKind.AUDIO)
                .thumbnailUrl("https://cdn/khi/clip.jpg")
                .captionCkb("ژێرنووس")
                .captionKmr("Jêrnivîs")
                .sortOrder(0)
                .build();
    }

    private static NewsDto.BilingualSet bilingualSet() {
        return NewsDto.BilingualSet.builder()
                .ckb(new LinkedHashSet<>(Set.of("تاگ")))
                .kmr(new LinkedHashSet<>(Set.of("etîket")))
                .build();
    }

    private static SoundTrackDtos.BilingualSet soundBilingualSet() {
        return SoundTrackDtos.BilingualSet.builder()
                .ckb(new LinkedHashSet<>(Set.of("تاگ")))
                .kmr(new LinkedHashSet<>(Set.of("etîket")))
                .build();
    }

    private static ImageCollectionDTO.BilingualSet imageBilingualSet() {
        return ImageCollectionDTO.BilingualSet.builder()
                .ckb(new LinkedHashSet<>(Set.of("تاگ")))
                .kmr(new LinkedHashSet<>(Set.of("etîket")))
                .build();
    }
}
