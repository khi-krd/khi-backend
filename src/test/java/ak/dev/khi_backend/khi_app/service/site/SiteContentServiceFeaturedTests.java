package ak.dev.khi_backend.khi_app.service.site;

import ak.dev.khi_backend.khi_app.enums.MediaKind;
import ak.dev.khi_backend.khi_app.model.news.News;
import ak.dev.khi_backend.khi_app.model.news.NewsContent;
import ak.dev.khi_backend.khi_app.model.site.SiteSettings;
import ak.dev.khi_backend.khi_app.model.publishment.image.ImageCollection;
import ak.dev.khi_backend.khi_app.model.publishment.image.ImageContent;
import ak.dev.khi_backend.khi_app.model.publishment.sound.SoundTrack;
import ak.dev.khi_backend.khi_app.model.publishment.sound.SoundTrackContent;
import ak.dev.khi_backend.khi_app.model.publishment.video.Video;
import ak.dev.khi_backend.khi_app.model.publishment.video.VideoContent;
import ak.dev.khi_backend.khi_app.model.publishment.writing.Writing;
import ak.dev.khi_backend.khi_app.model.publishment.writing.WritingContent;
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
import ak.dev.khi_backend.khi_app.repository.site.SocialLinkRepository;
import ak.dev.khi_backend.khi_app.repository.site.SiteSettingsRepository;
import ak.dev.khi_backend.khi_app.repository.site.TeamMemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiteContentServiceFeaturedTests {

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

    @Test
    void defaultsToSevenFeaturedSlidesWhenSettingsDoNotExist() {
        when(siteSettingsRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());

        assertThat(siteContentService.getSiteSettings().getMaxFeaturedSlides()).isEqualTo(7);
    }

    @Test
    void composesLocalizedFeaturedSlidesFromFlaggedContentEntities() {
        News news = News.builder()
                .id(42L)
                .coverUrl("https://cdn.example.com/news.jpg")
                .coverMediaType(MediaKind.IMAGE)
                .featured(true)
                .featuredOrder(1)
                .ckbContent(content("News CKB", "News description CKB"))
                .kmrContent(content("News KMR", "News description KMR"))
                .build();
        Writing writing = Writing.builder()
                .id(9L)
                .ckbCoverUrl("https://cdn.example.com/writing-ckb.jpg")
                .kmrCoverUrl("https://cdn.example.com/writing-kmr.jpg")
                .featured(true)
                .featuredOrder(2)
                .ckbContent(writingContent("Writing CKB", "Writing description CKB"))
                .kmrContent(writingContent("Writing KMR", "Writing description KMR"))
                .build();
        Video video = Video.builder()
                .id(15L)
                .ckbCoverUrl("https://cdn.example.com/video-ckb.jpg")
                .kmrCoverUrl("https://cdn.example.com/video-kmr.jpg")
                .featured(true)
                .featuredOrder(3)
                .ckbContent(videoContent("Video CKB", "Video description CKB"))
                .kmrContent(videoContent("Video KMR", "Video description KMR"))
                .build();
        SoundTrack sound = SoundTrack.builder()
                .id(21L)
                .ckbCoverUrl("https://cdn.example.com/sound-ckb.jpg")
                .kmrCoverUrl("https://cdn.example.com/sound-kmr.jpg")
                .featured(true)
                .featuredOrder(4)
                .ckbContent(soundContent("Sound CKB", "Sound description CKB"))
                .kmrContent(soundContent("Sound KMR", "Sound description KMR"))
                .build();
        ImageCollection images = ImageCollection.builder()
                .id(7L)
                .slugCkb("images-ckb")
                .slugKmr("images-kmr")
                .ckbCoverUrl("https://cdn.example.com/images-ckb.jpg")
                .kmrCoverUrl("https://cdn.example.com/images-kmr.jpg")
                .featured(true)
                .featuredOrder(5)
                .ckbContent(imageContent("Images CKB", "Images description CKB"))
                .kmrContent(imageContent("Images KMR", "Images description KMR"))
                .build();

        when(newsRepository.findByFeaturedTrueOrderByFeaturedOrderAscIdDesc())
                .thenReturn(List.of(news));
        when(projectRepository.findByFeaturedTrueOrderByFeaturedOrderAscIdDesc())
                .thenReturn(List.of());
        when(writingRepository.findFeaturedWithTopic()).thenReturn(List.of(writing));
        when(videoRepository.findFeaturedWithTopic()).thenReturn(List.of(video));
        when(soundTrackRepository.findByFeaturedTrueOrderByFeaturedOrderAscIdDesc())
                .thenReturn(List.of(sound));
        when(imageCollectionRepository.findByFeaturedTrueOrderByFeaturedOrderAscIdDesc())
                .thenReturn(List.of(images));
        when(siteSettingsRepository.findFirstByOrderByIdAsc())
                .thenReturn(Optional.of(
                        SiteSettings.builder().maxFeaturedSlides(5).build()));

        var result = siteContentService.getFeatured("kmr");

        assertThat(result).extracting("id")
                .containsExactly(
                        "news-42", "writing-9", "video-15",
                        "sound-track-21", "image-collection-7");
        assertThat(result).extracting("type")
                .containsExactly("article", "book", "video", "audio", "gallery");
        assertThat(result).extracting("title")
                .containsExactly(
                        "News KMR", "Writing KMR", "Video KMR", "Sound KMR", "Images KMR");
        assertThat(result).extracting(item -> item.getImage().getUrl())
                .containsExactly(
                        "https://cdn.example.com/news.jpg",
                        "https://cdn.example.com/writing-kmr.jpg",
                        "https://cdn.example.com/video-kmr.jpg",
                        "https://cdn.example.com/sound-kmr.jpg",
                        "https://cdn.example.com/images-kmr.jpg");
        assertThat(result).extracting("displayOrder")
                .containsExactly(1, 2, 3, 4, 5);
        assertThat(result).allMatch(item -> item.getActive() && "kmr".equals(item.getLocale()));
    }

    private NewsContent content(String title, String description) {
        return NewsContent.builder().title(title).description(description).build();
    }

    private WritingContent writingContent(String title, String description) {
        return WritingContent.builder().title(title).description(description).build();
    }

    private VideoContent videoContent(String title, String description) {
        return VideoContent.builder().title(title).description(description).build();
    }

    private SoundTrackContent soundContent(String title, String description) {
        return SoundTrackContent.builder().title(title).description(description).build();
    }

    private ImageContent imageContent(String title, String description) {
        return ImageContent.builder().title(title).description(description).build();
    }
}
