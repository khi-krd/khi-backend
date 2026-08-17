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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Featured behaviour for About, Service and the singleton Donation page.
 *
 * Donation is a hero slide. About and Service are NOT: their flag highlights the record on
 * its own page, so these tests assert their ABSENCE from the carousel and their freedom from
 * the slide cap. The six publication types are covered by {@link SiteContentServiceFeaturedTests}.
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
    void heroCarouselIgnoresFeaturedServicesAndAboutPages() {
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
                        serviceContent("CKB", "Service CKB", "<p>ignored</p>", "Card line CKB"),
                        serviceContent("KMR", "Service KMR", "<p>ignored</p>", "Card line KMR")))
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

        when(donationSettingsRepository.findAll()).thenReturn(List.of(donation));
        when(siteSettingsRepository.findFirstByOrderByIdAsc())
                .thenReturn(Optional.of(SiteSettings.builder().maxFeaturedSlides(5).build()));

        var result = siteContentService.getFeatured("kmr");

        // Both are featured, both have a resolvable image, and neither may reach the hero:
        // their flag highlights them on /services and /about instead.
        assertThat(result).extracting("source").containsExactly("donation");
        assertThat(result).extracting("id").containsExactly("donation-1");
        assertThat(result).extracting("title").containsExactly("Donation KMR");
        assertThat(result).extracting("displayOrder").containsExactly(1);

        // The carousel must not even ask those two repositories for candidates.
        verifyNoInteractions(aboutRepository);
        verify(serviceRepository, never()).findFeaturedWithContents();

        assertThat(about.isFeatured()).isTrue();
        assertThat(service.isFeatured()).isTrue();
    }

    @Test
    void refusesToFeatureAnAboutPageWithoutAFeatureImage() {
        About about = About.builder().id(5L).slugCkb("mission").build();
        when(aboutRepository.findById(5L)).thenReturn(Optional.of(about));

        assertThatThrownBy(() -> siteContentService.setAboutFeatured(
                5L, FeaturedRequest.builder().featured(true).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("becomes the About page hero image");

        assertThat(about.isFeatured()).isFalse();
        verify(aboutRepository, never()).save(any());
    }

    @Test
    void featuringAnAboutPageIgnoresTheHeroSlideCap() {
        About about = About.builder()
                .id(5L)
                .slugCkb("mission")
                .featureImageUrl("https://cdn.example.com/about.jpg")
                .build();
        when(aboutRepository.findById(5L)).thenReturn(Optional.of(about));
        when(aboutRepository.save(any(About.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        siteContentService.setAboutFeatured(
                5L, FeaturedRequest.builder().featured(true).featuredOrder(1).build());

        assertThat(about.isFeatured()).isTrue();
        assertThat(about.getFeaturedOrder()).isEqualTo(1);
        // No cap lookup at all: page highlights do not consume hero slides.
        verifyNoInteractions(siteSettingsRepository);
        verifyNoInteractions(newsRepository);
    }

    @Test
    void featuringAServiceSucceedsEvenWhenTheCarouselIsFull() {
        Service service = Service.builder()
                .id(16L)
                .navAnchorId("digital-archive")
                .galleryMedia(List.of(ServiceMedia.builder()
                        .type("IMAGE")
                        .url("https://cdn.example.com/gallery-1.jpg")
                        .build()))
                .build();
        when(serviceRepository.findById(16L)).thenReturn(Optional.of(service));
        when(serviceRepository.save(any(Service.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        siteContentService.setServiceFeatured(
                16L, FeaturedRequest.builder().featured(true).featuredOrder(2).build());

        assertThat(service.isFeatured()).isTrue();
        assertThat(service.getFeaturedOrder()).isEqualTo(2);
        // The gallery image satisfies the picture requirement, so no featureImageUrl was needed.
        assertThat(service.getFeatureImageUrl()).isNull();
        verifyNoInteractions(siteSettingsRepository);
    }

    @Test
    void unfeaturingAServiceClearsTheOrderButKeepsThePicture() {
        Service service = Service.builder()
                .id(16L)
                .featured(true)
                .featuredOrder(2)
                .featureImageUrl("https://cdn.example.com/service-hero.jpg")
                .build();
        when(serviceRepository.findById(16L)).thenReturn(Optional.of(service));
        when(serviceRepository.save(any(Service.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        siteContentService.setServiceFeatured(
                16L, FeaturedRequest.builder().featured(false).build());

        assertThat(service.isFeatured()).isFalse();
        assertThat(service.getFeaturedOrder()).isNull();
        assertThat(service.getFeatureImageUrl())
                .isEqualTo("https://cdn.example.com/service-hero.jpg");
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
