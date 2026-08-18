package ak.dev.khi_backend.khi_app.service.site;

import ak.dev.khi_backend.khi_app.dto.site.SiteContentDtos.SiteSettingsRequest;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Branding settings — the logo and the donate-band picture.
 *
 * <p>The rule the dashboard depends on: nothing on this resource is required, and each
 * field is tri-state — omitted leaves the stored value alone, {@code ""} clears it, a
 * value is trimmed and stored.</p>
 */
@ExtendWith(MockitoExtension.class)
class SiteContentServiceSiteSettingsTests {

    private static final String LOGO =
            "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/branding/khi-logo.png";
    private static final String DONATE =
            "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/branding/archive.jpg";

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
    void readAnswersWithDefaultsWhenNoRowIsStored() {
        when(siteSettingsRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());

        var result = siteContentService.getSiteSettings();

        // Never 404s — a fresh database still serves a usable response, so the website
        // falls back to its bundled logo and a plain donate band.
        assertThat(result.getLogoUrl()).isNull();
        assertThat(result.getDonateImageUrl()).isNull();
        assertThat(result.getMaxFeaturedSlides())
                .isEqualTo(SiteSettings.DEFAULT_MAX_FEATURED_SLIDES);
    }

    @Test
    void readReturnsBothBrandingImages() {
        when(siteSettingsRepository.findFirstByOrderByIdAsc())
                .thenReturn(Optional.of(stored(LOGO, DONATE)));

        var result = siteContentService.getSiteSettings();

        assertThat(result.getLogoUrl()).isEqualTo(LOGO);
        assertThat(result.getDonateImageUrl()).isEqualTo(DONATE);
    }

    // ── write path: the tri-state ────────────────────────────────────────────

    @Test
    void aUrlIsTrimmedAndStored() {
        SiteSettings saved = save(SiteSettingsRequest.builder()
                .logoUrl("  " + LOGO + "  ")
                .build());

        assertThat(saved.getLogoUrl()).isEqualTo(LOGO);
    }

    @Test
    void theFirstEverSaveFillsInTheSlideCap() {
        // Fresh database, no row yet, and the Branding screen posts only a picker.
        // max_featured_slides is NOT NULL, so the default has to be filled in here or
        // the insert fails.
        SiteSettings blank = new SiteSettings();
        blank.setMaxFeaturedSlides(null);

        SiteSettings saved = save(blank, SiteSettingsRequest.builder().logoUrl(LOGO).build());

        assertThat(saved.getMaxFeaturedSlides())
                .isEqualTo(SiteSettings.DEFAULT_MAX_FEATURED_SLIDES);
        assertThat(saved.getLogoUrl()).isEqualTo(LOGO);
    }

    @Test
    void anEmptyStringClearsTheField() {
        SiteSettings saved = save(stored(LOGO, DONATE),
                SiteSettingsRequest.builder().logoUrl("").build());

        assertThat(saved.getLogoUrl()).isNull();
        // The other picker is untouched.
        assertThat(saved.getDonateImageUrl()).isEqualTo(DONATE);
    }

    @Test
    void anOmittedFieldLeavesTheStoredValueAlone() {
        SiteSettings saved = save(stored(LOGO, DONATE),
                SiteSettingsRequest.builder().donateImageUrl(DONATE + "?v=2").build());

        assertThat(saved.getLogoUrl()).isEqualTo(LOGO);
        assertThat(saved.getDonateImageUrl()).isEqualTo(DONATE + "?v=2");
    }

    @Test
    void savingAnEmptyFormChangesNothing() {
        // No picker may block a save: an empty body is a legal no-op.
        SiteSettings saved = save(stored(LOGO, DONATE), SiteSettingsRequest.builder().build());

        assertThat(saved.getLogoUrl()).isEqualTo(LOGO);
        assertThat(saved.getDonateImageUrl()).isEqualTo(DONATE);
        assertThat(saved.getMaxFeaturedSlides()).isEqualTo(7);
    }

    @Test
    void brandingCanBeSavedWithoutSendingMaxFeaturedSlides() {
        // The dashboard's Branding screen posts only the pickers; the slide cap must
        // survive untouched rather than being nulled or rejected.
        SiteSettings saved = save(stored(null, null),
                SiteSettingsRequest.builder().logoUrl(LOGO).donateImageUrl(DONATE).build());

        assertThat(saved.getMaxFeaturedSlides()).isEqualTo(7);
        assertThat(saved.getLogoUrl()).isEqualTo(LOGO);
        assertThat(saved.getDonateImageUrl()).isEqualTo(DONATE);
    }

    @Test
    void maxFeaturedSlidesIsStillWritable() {
        SiteSettings saved = save(stored(LOGO, DONATE),
                SiteSettingsRequest.builder().maxFeaturedSlides(5).build());

        assertThat(saved.getMaxFeaturedSlides()).isEqualTo(5);
        assertThat(saved.getLogoUrl()).isEqualTo(LOGO);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private SiteSettings stored(String logoUrl, String donateImageUrl) {
        SiteSettings settings = new SiteSettings();
        settings.setId(1L);
        settings.setMaxFeaturedSlides(7);
        settings.setLogoUrl(logoUrl);
        settings.setDonateImageUrl(donateImageUrl);
        return settings;
    }

    private SiteSettings save(SiteSettingsRequest request) {
        return save(new SiteSettings(), request);
    }

    /** Runs the update against {@code existing} and returns the row handed to save(). */
    private SiteSettings save(SiteSettings existing, SiteSettingsRequest request) {
        when(siteSettingsRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(existing));
        when(siteSettingsRepository.save(any(SiteSettings.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        siteContentService.updateSiteSettings(request);
        return existing;
    }
}
