package ak.dev.khi_backend.khi_app.service.site;

import ak.dev.khi_backend.khi_app.dto.site.SiteContentDtos.FeaturedRequest;
import ak.dev.khi_backend.khi_app.model.about.About;
import ak.dev.khi_backend.khi_app.model.about.AboutContent;
import ak.dev.khi_backend.khi_app.model.service.Service;
import ak.dev.khi_backend.khi_app.model.service.ServiceContent;
import ak.dev.khi_backend.khi_app.model.service.ServiceMedia;
import ak.dev.khi_backend.khi_app.model.site.DonationSettings;
import ak.dev.khi_backend.khi_app.model.site.SiteSettings;
import ak.dev.khi_backend.khi_app.repository.about.AboutRepository;
import ak.dev.khi_backend.khi_app.repository.news.NewsRepository;
import ak.dev.khi_backend.khi_app.repository.project.ProjectRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.image.ImageCollectionRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.sound.SoundTrackRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.video.VideoRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.writing.WritingRepository;
import ak.dev.khi_backend.khi_app.repository.service.ServiceRepository;
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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Featured behaviour for the three institutional sources — About, Service and the
 * singleton Donation page. The six publication types are covered by
 * {@link SiteContentServiceFeaturedTests}.
 */
@ExtendWith(MockitoExtension.class)
class SiteContentServiceInstitutionalFeaturedTests {

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
    void composesSlidesForAboutServiceAndDonation() {
        About about = About.builder()
                .id(5L)
                .slugCkb("derbare-me-ckb")
                .slugKmr("derbare-me-kmr")
                .featured(true)
                .featuredOrder(1)
                .featureImageUrl("https://cdn.example.com/about-hero.jpg")
                .ckbContent(aboutContent("About CKB", "Subtitle CKB", "Meta CKB"))
                .kmrContent(aboutContent("About KMR", "Subtitle KMR", "Meta KMR"))
                .build();

        Service service = Service.builder()
                .id(3L)
                .navAnchorId("recording-studio")
                .featured(true)
                .featuredOrder(2)
                .featureImageUrl("https://cdn.example.com/service-hero.jpg")
                .contents(contents(
                        serviceContent("CKB", "Service CKB", "<p>ignored</p>", "Slide line CKB"),
                        serviceContent("KMR", "Service KMR", "<p>ignored</p>", "Slide line KMR")))
                .build();

        DonationSettings donation = DonationSettings.builder()
                .id(1L)
                .titleCkb("Donation CKB")
                .titleKmr("Donation KMR")
                .descriptionCkb("Donation description CKB")
                .descriptionKmr("Donation description KMR")
                .heroImageUrl("https://cdn.example.com/donation-hero.jpg")
                .featured(true)
                .featuredOrder(3)
                .build();

        when(aboutRepository.findByFeaturedTrueOrderByFeaturedOrderAscIdDesc())
                .thenReturn(List.of(about));
        when(serviceRepository.findFeaturedWithContents()).thenReturn(List.of(service));
        when(donationSettingsRepository.findAll()).thenReturn(List.of(donation));
        when(siteSettingsRepository.findFirstByOrderByIdAsc())
                .thenReturn(Optional.of(SiteSettings.builder().maxFeaturedSlides(5).build()));

        var result = siteContentService.getFeatured("kmr");

        assertThat(result).extracting("id")
                .containsExactly("about-5", "service-3", "donation-1");
        assertThat(result).extracting("source")
                .containsExactly("about", "service", "donation");
        assertThat(result).extracting("type")
                .containsExactly("about", "service", "donation");
        assertThat(result).extracting("slug")
                .containsExactly("derbare-me-kmr", "recording-studio", "donation");
        assertThat(result).extracting("title")
                .containsExactly("About KMR", "Service KMR", "Donation KMR");
        assertThat(result).extracting("description")
                .containsExactly("Subtitle KMR", "Slide line KMR", "Donation description KMR");
        assertThat(result).extracting(slide -> slide.getImage().getUrl())
                .containsExactly(
                        "https://cdn.example.com/about-hero.jpg",
                        "https://cdn.example.com/service-hero.jpg",
                        "https://cdn.example.com/donation-hero.jpg");
        assertThat(result).extracting("displayOrder").containsExactly(1, 2, 3);
    }

    @Test
    void aboutSlideFallsBackToMetaDescriptionWhenSubtitleIsBlank() {
        About about = About.builder()
                .id(8L)
                .slugCkb("mission")
                .featured(true)
                .featureImageUrl("https://cdn.example.com/about.jpg")
                .ckbContent(aboutContent("About CKB", null, "Meta CKB"))
                .build();

        when(aboutRepository.findByFeaturedTrueOrderByFeaturedOrderAscIdDesc())
                .thenReturn(List.of(about));

        var result = siteContentService.getFeatured("ckb");

        assertThat(result).singleElement()
                .extracting("description").isEqualTo("Meta CKB");
    }

    @Test
    void serviceSlideStripsTiptapHtmlWhenNoFeatureDescriptionIsAuthored() {
        Service service = Service.builder()
                .id(11L)
                .featured(true)
                .galleryMedia(List.of(
                        ServiceMedia.builder()
                                .type("VIDEO")
                                .url("https://cdn.example.com/clip.mp4")
                                .posterUrl("https://cdn.example.com/clip-poster.jpg")
                                .build()))
                .contents(contents(serviceContent(
                        "CKB", "Studio",
                        "<p>Full <strong>recording</strong> studio&nbsp;service.</p>", null)))
                .build();

        when(serviceRepository.findFeaturedWithContents()).thenReturn(List.of(service));

        var result = siteContentService.getFeatured("ckb");

        assertThat(result).singleElement()
                .satisfies(slide -> {
                    assertThat(slide.getDescription())
                            .isEqualTo("Full recording studio service.");
                    // No featureImageUrl and no image slot — a video slot's poster is used.
                    assertThat(slide.getImage().getUrl())
                            .isEqualTo("https://cdn.example.com/clip-poster.jpg");
                    assertThat(slide.getSlug()).isEqualTo("11");
                });
    }

    @Test
    void refusesToFeatureAnAboutPageWithoutAFeatureImage() {
        About about = About.builder().id(5L).slugCkb("mission").build();
        when(aboutRepository.findById(5L)).thenReturn(Optional.of(about));

        assertThatThrownBy(() -> siteContentService.setAboutFeatured(
                5L, FeaturedRequest.builder().featured(true).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("featureImageUrl is required");

        assertThat(about.isFeatured()).isFalse();
        verify(aboutRepository, never()).save(any());
    }

    @Test
    void countsInstitutionalSourcesAgainstTheGlobalSlideCap() {
        About about = About.builder()
                .id(5L)
                .slugCkb("mission")
                .featureImageUrl("https://cdn.example.com/about.jpg")
                .build();
        when(aboutRepository.findById(5L)).thenReturn(Optional.of(about));
        when(siteSettingsRepository.findFirstByOrderByIdAsc())
                .thenReturn(Optional.of(SiteSettings.builder().maxFeaturedSlides(2).build()));
        // One featured service + the featured donation page already fill the cap of 2.
        when(serviceRepository.countByFeaturedTrue()).thenReturn(1L);
        when(donationSettingsRepository.findAll())
                .thenReturn(List.of(DonationSettings.builder().id(1L).featured(true).build()));

        assertThatThrownBy(() -> siteContentService.setAboutFeatured(
                5L, FeaturedRequest.builder().featured(true).build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Maximum of 2 featured slides");

        verify(aboutRepository, never()).save(any());
    }

    @Test
    void featuresTheDonationPageUsingItsHeroImageAsFallback() {
        DonationSettings settings = DonationSettings.builder()
                .id(1L)
                .titleCkb("Donation")
                .heroImageUrl("https://cdn.example.com/donation-hero.jpg")
                .build();
        when(donationSettingsRepository.findAll()).thenReturn(List.of(settings));
        when(donationSettingsRepository.save(any(DonationSettings.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = siteContentService.setDonationFeatured(
                FeaturedRequest.builder().featured(true).featuredOrder(2).build());

        assertThat(response.getFeatured()).isTrue();
        assertThat(response.getFeaturedOrder()).isEqualTo(2);
        assertThat(settings.isFeatured()).isTrue();
    }

    @Test
    void unfeaturingTheDonationPageClearsItsOrder() {
        DonationSettings settings = DonationSettings.builder()
                .id(1L)
                .featured(true)
                .featuredOrder(3)
                .heroImageUrl("https://cdn.example.com/donation-hero.jpg")
                .build();
        when(donationSettingsRepository.findAll()).thenReturn(List.of(settings));
        when(donationSettingsRepository.save(any(DonationSettings.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = siteContentService.setDonationFeatured(
                FeaturedRequest.builder().featured(false).build());

        assertThat(response.getFeatured()).isFalse();
        assertThat(response.getFeaturedOrder()).isNull();
    }

    // ─── Fixtures ─────────────────────────────────────────────────────────────

    private AboutContent aboutContent(String title, String subtitle, String metaDescription) {
        return AboutContent.builder()
                .title(title)
                .subtitle(subtitle)
                .metaDescription(metaDescription)
                .build();
    }

    private ServiceContent serviceContent(
            String languageCode, String title, String description, String featureDescription) {
        return ServiceContent.builder()
                .languageCode(languageCode)
                .title(title)
                .description(description)
                .featureDescription(featureDescription)
                .build();
    }

    private Set<ServiceContent> contents(ServiceContent... rows) {
        return new LinkedHashSet<>(List.of(rows));
    }
}
