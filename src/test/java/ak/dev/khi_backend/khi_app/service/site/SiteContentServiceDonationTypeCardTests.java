package ak.dev.khi_backend.khi_app.service.site;

import ak.dev.khi_backend.khi_app.dto.site.SiteContentDtos.DonationTypeCardRequest;
import ak.dev.khi_backend.khi_app.dto.site.SiteContentDtos.DonationTypeCardResponse;
import ak.dev.khi_backend.khi_app.model.site.DonationTypeCard;
import ak.dev.khi_backend.khi_app.repository.site.DonationTypeCardRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiteContentServiceDonationTypeCardTests {

    @Mock private DonationTypeCardRepository donationTypeCardRepository;

    @InjectMocks
    private SiteContentService siteContentService;

    @Test
    void getUsesActiveOnlyQueryByDefaultAndFullQueryForDashboard() {
        when(donationTypeCardRepository.findAllByActiveTrueOrderByDisplayOrderAsc())
                .thenReturn(List.of());
        when(donationTypeCardRepository.findAllByOrderByDisplayOrderAsc())
                .thenReturn(List.of());

        assertThat(siteContentService.getDonationTypeCards(false)).isEmpty();
        verify(donationTypeCardRepository).findAllByActiveTrueOrderByDisplayOrderAsc();

        assertThat(siteContentService.getDonationTypeCards(true)).isEmpty();
        verify(donationTypeCardRepository).findAllByOrderByDisplayOrderAsc();
    }

    @Test
    void createTrimsBlanksToNullAndAppliesDefaults() {
        when(donationTypeCardRepository.save(any(DonationTypeCard.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DonationTypeCardResponse response = siteContentService.createDonationTypeCard(
                DonationTypeCardRequest.builder()
                        .titleCkb("  ئەرشیفی بینراو  ")
                        .titleKmr("   ")
                        .descriptionCkb("")
                        .imageUrl("  https://s3/img.jpg  ")
                        .build());

        assertThat(response.getTitleCkb()).isEqualTo("ئەرشیفی بینراو");
        assertThat(response.getTitleKmr()).isNull();
        assertThat(response.getDescriptionCkb()).isNull();
        assertThat(response.getImageUrl()).isEqualTo("https://s3/img.jpg");
        assertThat(response.getDisplayOrder()).isZero();
        assertThat(response.getActive()).isTrue();
    }

    @Test
    void createRejectsCardWithNoTitleInEitherLanguage() {
        assertThatThrownBy(() -> siteContentService.createDonationTypeCard(
                DonationTypeCardRequest.builder()
                        .titleCkb("  ")
                        .imageUrl("https://s3/img.jpg")
                        .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("titleCkb");
        verify(donationTypeCardRepository, never()).save(any());
    }

    @Test
    void updateReplacesTheWholeRow() {
        DonationTypeCard existing = DonationTypeCard.builder()
                .id(7L).titleCkb("old").descriptionCkb("old desc")
                .imageUrl("https://s3/old.jpg").displayOrder(3).active(true)
                .build();
        when(donationTypeCardRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(donationTypeCardRepository.save(any(DonationTypeCard.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DonationTypeCardResponse response = siteContentService.updateDonationTypeCard(7L,
                DonationTypeCardRequest.builder()
                        .titleKmr("Belge")
                        .imageUrl("https://s3/new.jpg")
                        .active(false)
                        .build());

        // Full replace: fields the request omits are blanked, not kept.
        assertThat(response.getTitleCkb()).isNull();
        assertThat(response.getTitleKmr()).isEqualTo("Belge");
        assertThat(response.getDescriptionCkb()).isNull();
        assertThat(response.getImageUrl()).isEqualTo("https://s3/new.jpg");
        assertThat(response.getDisplayOrder()).isZero();
        assertThat(response.getActive()).isFalse();
    }

    @Test
    void updateAndDeleteOnUnknownIdThrowNotFound() {
        when(donationTypeCardRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> siteContentService.updateDonationTypeCard(99L,
                DonationTypeCardRequest.builder()
                        .titleCkb("x").imageUrl("https://s3/x.jpg").build()))
                .hasMessageContaining("Donation type card not found: 99");

        when(donationTypeCardRepository.existsById(99L)).thenReturn(false);
        assertThatThrownBy(() -> siteContentService.deleteDonationTypeCard(99L))
                .hasMessageContaining("Donation type card not found: 99");
    }
}
