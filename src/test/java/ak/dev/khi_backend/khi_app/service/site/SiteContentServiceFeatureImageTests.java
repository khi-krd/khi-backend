package ak.dev.khi_backend.khi_app.service.site;

import ak.dev.khi_backend.khi_app.dto.site.SiteContentDtos.FeaturedRequest;
import ak.dev.khi_backend.khi_app.enums.MediaKind;
import ak.dev.khi_backend.khi_app.model.news.News;
import ak.dev.khi_backend.khi_app.model.news.NewsContent;
import ak.dev.khi_backend.khi_app.model.project.Project;
import ak.dev.khi_backend.khi_app.model.project.ProjectContentBlock;
import ak.dev.khi_backend.khi_app.model.publishment.image.ImageCollection;
import ak.dev.khi_backend.khi_app.model.publishment.image.ImageContent;
import ak.dev.khi_backend.khi_app.model.publishment.sound.SoundTrack;
import ak.dev.khi_backend.khi_app.model.publishment.sound.SoundTrackContent;
import ak.dev.khi_backend.khi_app.model.publishment.video.Video;
import ak.dev.khi_backend.khi_app.model.publishment.video.VideoContent;
import ak.dev.khi_backend.khi_app.model.publishment.writing.Writing;
import ak.dev.khi_backend.khi_app.model.publishment.writing.WritingContent;
import ak.dev.khi_backend.khi_app.model.site.SiteSettings;
import ak.dev.khi_backend.khi_app.repository.about.AboutRepository;
import ak.dev.khi_backend.khi_app.repository.news.NewsRepository;
import ak.dev.khi_backend.khi_app.repository.project.ProjectRepository;
import ak.dev.khi_backend.khi_app.repository.service.ServiceRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.image.ImageCollectionRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.sound.SoundTrackRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.video.VideoRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.writing.WritingRepository;
import ak.dev.khi_backend.khi_app.repository.site.ArchiveDonationRepository;
import ak.dev.khi_backend.khi_app.repository.site.ContactMessageRepository;
import ak.dev.khi_backend.khi_app.repository.site.DonationSettingsRepository;
import ak.dev.khi_backend.khi_app.repository.site.FinancialDonationRepository;
import ak.dev.khi_backend.khi_app.repository.site.PartnerRepository;
import ak.dev.khi_backend.khi_app.repository.site.SiteSettingsRepository;
import ak.dev.khi_backend.khi_app.repository.site.SocialLinkRepository;
import ak.dev.khi_backend.khi_app.repository.site.TeamMemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The hero picture: {@code featureImageUrl} wins over the cover on every one of the
 * six featured types, and the featured PATCH treats {@code null} as "leave it alone".
 */
@ExtendWith(MockitoExtension.class)
class SiteContentServiceFeatureImageTests {

    private static final String HERO = "https://cdn.example.com/hero-2560.jpg";

    @Mock private TeamMemberRepository teamRepository;
    @Mock private PartnerRepository partnerRepository;
    @Mock private ContactMessageRepository contactMessageRepository;
    @Mock private SocialLinkRepository socialLinkRepository;
    @Mock private DonationSettingsRepository donationSettingsRepository;
    @Mock private FinancialDonationRepository financialDonationRepository;
    @Mock private ArchiveDonationRepository archiveDonationRepository;
    @Mock private SiteSettingsRepository siteSettingsRepository;
    @Mock private NewsRepository newsRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private WritingRepository writingRepository;
    @Mock private VideoRepository videoRepository;
    @Mock private SoundTrackRepository soundTrackRepository;
    @Mock private ImageCollectionRepository imageCollectionRepository;
    @Mock private AboutRepository aboutRepository;
    @Mock private ServiceRepository serviceRepository;

    @InjectMocks
    private SiteContentService siteContentService;

    // ── read path ────────────────────────────────────────────────────────────

    @Test
    void featureImageWinsOverTheCoverOnAllSixTypes() {
        stubFeaturedRecords(HERO);

        var result = siteContentService.getFeatured("ckb");

        assertThat(result).hasSize(6);
        assertThat(result).extracting(slide -> slide.getImage().getUrl())
                .containsOnly(HERO);
    }

    @Test
    void coverIsUsedWhenNoFeatureImageIsSet() {
        stubFeaturedRecords(null);

        var result = siteContentService.getFeatured("ckb");

        assertThat(result).extracting("source")
                .containsExactly("news", "project", "writing", "video",
                        "sound-track", "image-collection");
        assertThat(result).extracting(slide -> slide.getImage().getUrl())
                .containsExactly(
                        "https://cdn.example.com/news.jpg",
                        "https://cdn.example.com/project.jpg",
                        "https://cdn.example.com/writing-ckb.jpg",
                        "https://cdn.example.com/video-ckb.jpg",
                        "https://cdn.example.com/sound-ckb.jpg",
                        "https://cdn.example.com/images-ckb.jpg");
    }

    @Test
    void blankFeatureImageFallsBackToTheCover() {
        stubFeaturedRecords("   ");

        var result = siteContentService.getFeatured("ckb");

        assertThat(result).extracting(slide -> slide.getImage().getUrl())
                .doesNotContain("   ")
                .contains("https://cdn.example.com/news.jpg");
    }

    // ── write path ───────────────────────────────────────────────────────────

    @Test
    void omittedFeatureImageLeavesTheStoredOneAlone() {
        News news = News.builder().id(1L).featured(true).featureImageUrl(HERO).build();
        when(newsRepository.findById(1L)).thenReturn(Optional.of(news));

        // a plain reorder — the dashboard sends only featured + featuredOrder
        siteContentService.setNewsFeatured(1L,
                FeaturedRequest.builder().featured(true).featuredOrder(3).build());

        assertThat(news.getFeatureImageUrl()).isEqualTo(HERO);
        assertThat(news.getFeaturedOrder()).isEqualTo(3);
    }

    @Test
    void emptyStringClearsTheFeatureImage() {
        News news = News.builder().id(1L).featured(true).featureImageUrl(HERO).build();
        when(newsRepository.findById(1L)).thenReturn(Optional.of(news));

        siteContentService.setNewsFeatured(1L,
                FeaturedRequest.builder().featured(true).featureImageUrl("").build());

        assertThat(news.getFeatureImageUrl()).isNull();
    }

    @Test
    void featureImageIsTrimmedWhenSet() {
        Writing writing = Writing.builder().id(4L).featured(true).build();
        when(writingRepository.findById(4L)).thenReturn(Optional.of(writing));

        siteContentService.setWritingFeatured(4L,
                FeaturedRequest.builder().featured(true).featureImageUrl("  " + HERO + "  ").build());

        assertThat(writing.getFeatureImageUrl()).isEqualTo(HERO);
    }

    @Test
    void unfeaturingKeepsTheFeatureImageForNextTime() {
        Video video = Video.builder().id(6L).featured(true).featuredOrder(2)
                .featureImageUrl(HERO).build();
        when(videoRepository.findById(6L)).thenReturn(Optional.of(video));

        siteContentService.setVideoFeatured(6L,
                FeaturedRequest.builder().featured(false).build());

        assertThat(video.isFeatured()).isFalse();
        assertThat(video.getFeaturedOrder()).isNull();
        assertThat(video.getFeatureImageUrl()).isEqualTo(HERO);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** One featured record of each type; {@code featureImage} is applied to all six. */
    private void stubFeaturedRecords(String featureImage) {
        News news = News.builder()
                .id(42L)
                .coverUrl("https://cdn.example.com/news.jpg")
                .coverMediaType(MediaKind.IMAGE)
                .featured(true).featuredOrder(1)
                .featureImageUrl(featureImage)
                .ckbContent(NewsContent.builder().title("News").description("d").build())
                .build();
        Project project = Project.builder()
                .id(3L)
                .coverUrl("https://cdn.example.com/project.jpg")
                .coverMediaType(MediaKind.IMAGE)
                .featured(true).featuredOrder(2)
                .featureImageUrl(featureImage)
                .ckbContent(ProjectContentBlock.builder().title("Project").description("d").build())
                .build();
        Writing writing = Writing.builder()
                .id(9L)
                .ckbCoverUrl("https://cdn.example.com/writing-ckb.jpg")
                .featured(true).featuredOrder(3)
                .featureImageUrl(featureImage)
                .ckbContent(WritingContent.builder().title("Writing").description("d").build())
                .build();
        Video video = Video.builder()
                .id(15L)
                .ckbCoverUrl("https://cdn.example.com/video-ckb.jpg")
                .featured(true).featuredOrder(4)
                .featureImageUrl(featureImage)
                .ckbContent(VideoContent.builder().title("Video").description("d").build())
                .build();
        SoundTrack sound = SoundTrack.builder()
                .id(21L)
                .ckbCoverUrl("https://cdn.example.com/sound-ckb.jpg")
                .featured(true).featuredOrder(5)
                .featureImageUrl(featureImage)
                .ckbContent(SoundTrackContent.builder().title("Sound").description("d").build())
                .build();
        ImageCollection images = ImageCollection.builder()
                .id(7L)
                .slugCkb("images-ckb")
                .ckbCoverUrl("https://cdn.example.com/images-ckb.jpg")
                .featured(true).featuredOrder(6)
                .featureImageUrl(featureImage)
                .ckbContent(ImageContent.builder().title("Images").description("d").build())
                .build();

        lenient().when(newsRepository.findByFeaturedTrueOrderByFeaturedOrderAscIdDesc())
                .thenReturn(List.of(news));
        lenient().when(projectRepository.findByFeaturedTrueOrderByFeaturedOrderAscIdDesc())
                .thenReturn(List.of(project));
        lenient().when(writingRepository.findFeaturedWithTopic()).thenReturn(List.of(writing));
        lenient().when(videoRepository.findFeaturedWithTopic()).thenReturn(List.of(video));
        lenient().when(soundTrackRepository.findByFeaturedTrueOrderByFeaturedOrderAscIdDesc())
                .thenReturn(List.of(sound));
        lenient().when(imageCollectionRepository.findByFeaturedTrueOrderByFeaturedOrderAscIdDesc())
                .thenReturn(List.of(images));
        lenient().when(siteSettingsRepository.findFirstByOrderByIdAsc())
                .thenReturn(Optional.of(SiteSettings.builder().maxFeaturedSlides(10).build()));
    }
}
