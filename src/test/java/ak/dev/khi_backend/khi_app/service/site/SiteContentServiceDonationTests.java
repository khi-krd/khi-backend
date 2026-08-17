package ak.dev.khi_backend.khi_app.service.site;

import ak.dev.khi_backend.khi_app.dto.site.SiteContentDtos.ArchiveDonationRequest;
import ak.dev.khi_backend.khi_app.dto.site.SiteContentDtos.ArchiveDonationResponse;
import ak.dev.khi_backend.khi_app.dto.site.SiteContentDtos.FinancialDonationRequest;
import ak.dev.khi_backend.khi_app.dto.site.SiteContentDtos.FinancialDonationResponse;
import ak.dev.khi_backend.khi_app.model.site.ArchiveDonation;
import ak.dev.khi_backend.khi_app.model.site.FinancialDonation;
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

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiteContentServiceDonationTests {

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
    void financialDonationAcceptsEmptyEmailAndUppercasesCurrency() {
        when(financialDonationRepository.save(any(FinancialDonation.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        FinancialDonationResponse response = siteContentService.submitFinancialDonation(
                FinancialDonationRequest.builder()
                        .donorName("Sara Mohammed")
                        .email("")                       // site always sends ""
                        .amount(new BigDecimal("50000"))
                        .currency("iqd")
                        .paymentMethod("BANK_TRANSFER")
                        .build());

        assertThat(response.getEmail()).isEqualTo("");
        assertThat(response.getCurrency()).isEqualTo("IQD");
        assertThat(response.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void financialDonationRejectsUnsupportedCurrency() {
        assertThatThrownBy(() -> siteContentService.submitFinancialDonation(
                FinancialDonationRequest.builder()
                        .donorName("Sara")
                        .email("")
                        .amount(new BigDecimal("100"))
                        .currency("EUR")
                        .paymentMethod("BANK_TRANSFER")
                        .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");

        verify(financialDonationRepository, never()).save(any());
    }

    @Test
    void archiveDonationAcceptsMissingOptionalFields() {
        when(archiveDonationRepository.save(any(ArchiveDonation.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // No email, no title, no description — all optional per spec.
        ArchiveDonationResponse response = siteContentService.submitArchiveDonation(
                ArchiveDonationRequest.builder()
                        .donorName("Ahmed Hassan")
                        .phone("07701234567")
                        .materialType("photograph")     // lower-case accepted, normalized
                        .build());

        assertThat(response.getEmail()).isEqualTo("");
        assertThat(response.getTitle()).isEqualTo("");
        assertThat(response.getDescription()).isEqualTo("");
        assertThat(response.getMaterialType()).isEqualTo("PHOTOGRAPH");
        assertThat(response.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void archiveDonationRejectsUnsupportedMaterialType() {
        assertThatThrownBy(() -> siteContentService.submitArchiveDonation(
                ArchiveDonationRequest.builder()
                        .donorName("Ahmed")
                        .phone("07700000000")
                        .materialType("SCROLL")
                        .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("materialType");

        verify(archiveDonationRepository, never()).save(any());
    }

    @Test
    void archiveSubmissionBlockedWhenArchiveDisabled() {
        lenient().when(donationSettingsRepository.findAll()).thenReturn(java.util.List.of(
                ak.dev.khi_backend.khi_app.model.site.DonationSettings.builder()
                        .archiveDonationsEnabled(false)
                        .financialDonationsEnabled(true)
                        .build()));

        assertThatThrownBy(() -> siteContentService.submitArchiveDonation(
                ArchiveDonationRequest.builder()
                        .donorName("Ahmed")
                        .phone("07700000000")
                        .materialType("PHOTOGRAPH")
                        .build()))
                .isInstanceOf(IllegalStateException.class);

        verify(archiveDonationRepository, never()).save(any());
    }
}
