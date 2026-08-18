package ak.dev.khi_backend.khi_app.api.site;

import ak.dev.khi_backend.khi_app.dto.ApiResponse;
import ak.dev.khi_backend.khi_app.dto.site.SiteContentDtos.*;
import ak.dev.khi_backend.khi_app.service.site.SiteContentService;
import ak.dev.khi_backend.khi_app.service.site.SitemapService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Public Site", description = "Homepage, institutional content, settings, contact and donation flows")
public class PublicSiteController {

    private final SiteContentService siteContentService;
    private final SitemapService sitemapService;

    // Featured homepage hero

    // Public — consumed by khi-website's homepage hero. No auth required.
    @GetMapping("/featured")
    public ResponseEntity<List<FeaturedResponse>> getFeatured(
            @RequestParam(required = false) String locale) {
        return ResponseEntity.ok(siteContentService.getFeatured(locale));
    }



    // About team and partners

    @GetMapping("/about/team")
    public ApiResponse<List<TeamMemberResponse>> getTeam() {
        return ApiResponse.success(siteContentService.getTeam(), "Team members fetched");
    }

    @PostMapping("/about/team")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TeamMemberResponse> createTeamMember(
            @Valid @RequestBody TeamMemberRequest request) {
        return ApiResponse.success(siteContentService.createTeamMember(request), "Team member created");
    }

    @PutMapping("/about/team/{id}")
    public ApiResponse<TeamMemberResponse> updateTeamMember(
            @PathVariable Long id, @Valid @RequestBody TeamMemberRequest request) {
        return ApiResponse.success(siteContentService.updateTeamMember(id, request), "Team member updated");
    }

    @DeleteMapping("/about/team/{id}")
    public ApiResponse<Void> deleteTeamMember(@PathVariable Long id) {
        siteContentService.deleteTeamMember(id);
        return ApiResponse.success(null, "Team member deleted");
    }

    @GetMapping("/about/partners")
    public ApiResponse<List<PartnerResponse>> getPartners() {
        return ApiResponse.success(siteContentService.getPartners(), "Partners fetched");
    }

    @PostMapping("/about/partners")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PartnerResponse> createPartner(@Valid @RequestBody PartnerRequest request) {
        return ApiResponse.success(siteContentService.createPartner(request), "Partner created");
    }

    @PutMapping("/about/partners/{id}")
    public ApiResponse<PartnerResponse> updatePartner(
            @PathVariable Long id, @Valid @RequestBody PartnerRequest request) {
        return ApiResponse.success(siteContentService.updatePartner(id, request), "Partner updated");
    }

    @DeleteMapping("/about/partners/{id}")
    public ApiResponse<Void> deletePartner(@PathVariable Long id) {
        siteContentService.deletePartner(id);
        return ApiResponse.success(null, "Partner deleted");
    }

    // Contact form

    @PostMapping("/contact/messages")
    public ResponseEntity<ApiResponse<ContactMessageResponse>> submitContactMessage(
            @Valid @RequestBody ContactMessageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success(siteContentService.submitContactMessage(request),
                        "Contact message received"));
    }

    @GetMapping("/contact/messages")
    public ApiResponse<Page<ContactMessageResponse>> getContactMessages(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(siteContentService.getContactMessages(page, size),
                "Contact messages fetched");
    }

    @PatchMapping("/contact/messages/{id}/status")
    public ApiResponse<ContactMessageResponse> updateContactMessageStatus(
            @PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        return ApiResponse.success(siteContentService.updateContactMessageStatus(id, request),
                "Contact message status updated");
    }

    // Branding and global site settings
    //
    // Public read so the website can pick up the logo and the donate-band picture on
    // first paint; admin write from the dashboard's Branding screen. Both fields are
    // nullable and every field on the request is optional — the website has a working
    // fallback for each, so saving an empty form is legal and does nothing.

    /**
     * Read branding and global site settings.
     *
     * <p>Never 404s: with no row stored yet it returns the defaults, so a fresh
     * database serves a usable response.</p>
     */
    @GetMapping("/site-settings")
    public ApiResponse<SiteSettingsResponse> getSiteSettings() {
        return ApiResponse.success(siteContentService.getSiteSettings(), "Site settings fetched");
    }

    /**
     * Save branding and global site settings.
     *
     * <p>Tri-state per field, as with {@code featureImageUrl}: omitted leaves the
     * stored value alone, {@code ""} clears it, a value is trimmed and stored. URLs
     * must be absolute {@code https://} — the website and the API are on different
     * hosts and insecure requests are upgraded.</p>
     */
    @PutMapping("/site-settings")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ApiResponse<SiteSettingsResponse> updateSiteSettings(
            @Valid @RequestBody SiteSettingsRequest request) {
        return ApiResponse.success(siteContentService.updateSiteSettings(request),
                "Site settings updated");
    }

    // Global social settings

    @GetMapping("/settings/social")
    public ApiResponse<List<SocialLinkResponse>> getSocialLinks() {
        return ApiResponse.success(siteContentService.getSocialLinks(), "Social links fetched");
    }

    @PostMapping("/settings/social")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SocialLinkResponse> createSocialLink(
            @Valid @RequestBody SocialLinkRequest request) {
        return ApiResponse.success(siteContentService.createSocialLink(request), "Social link created");
    }

    @PutMapping("/settings/social/{id}")
    public ApiResponse<SocialLinkResponse> updateSocialLink(
            @PathVariable Long id, @Valid @RequestBody SocialLinkRequest request) {
        return ApiResponse.success(siteContentService.updateSocialLink(id, request), "Social link updated");
    }

    @DeleteMapping("/settings/social/{id}")
    public ApiResponse<Void> deleteSocialLink(@PathVariable Long id) {
        siteContentService.deleteSocialLink(id);
        return ApiResponse.success(null, "Social link deleted");
    }

    // Donation page and submission workflows

    @GetMapping("/donations/settings")
    public ApiResponse<DonationSettingsResponse> getDonationSettings() {
        return ApiResponse.success(siteContentService.getDonationSettings(), "Donation settings fetched");
    }

    @GetMapping("/donations/types")
    public ApiResponse<List<DonationTypeResponse>> getDonationTypes() {
        return ApiResponse.success(siteContentService.getDonationTypes(), "Donation types fetched");
    }

    @PutMapping("/donations/settings")
    public ApiResponse<DonationSettingsResponse> saveDonationSettings(
            @Valid @RequestBody DonationSettingsRequest request) {
        return ApiResponse.success(siteContentService.saveDonationSettings(request),
                "Donation settings saved");
    }

    /**
     * Feature / unfeature the donation page on the homepage carousel.
     *
     * Donation is a singleton settings row, so there is no id in the path — this
     * is the donation counterpart of {@code PATCH /{resource}/{id}/featured}.
     * The slide image falls back to heroImageUrl when featureImageUrl is blank.
     */
    @PatchMapping("/donations/settings/featured")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<DonationSettingsResponse> setDonationFeatured(
            @RequestBody FeaturedRequest request) {
        return ApiResponse.success(siteContentService.setDonationFeatured(request),
                "Donation featured state updated");
    }

    @PostMapping("/donations/financial")
    public ResponseEntity<ApiResponse<FinancialDonationResponse>> submitFinancialDonation(
            @Valid @RequestBody FinancialDonationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                siteContentService.submitFinancialDonation(request), "Financial donation received"));
    }

    @PostMapping("/donations/archive")
    public ResponseEntity<ApiResponse<ArchiveDonationResponse>> submitArchiveDonation(
            @Valid @RequestBody ArchiveDonationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                siteContentService.submitArchiveDonation(request), "Archive donation offer received"));
    }

    @GetMapping("/donations/financial")
    public ApiResponse<Page<FinancialDonationResponse>> getFinancialDonations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(siteContentService.getFinancialDonations(page, size),
                "Financial donations fetched");
    }

    @GetMapping("/donations/archive")
    public ApiResponse<Page<ArchiveDonationResponse>> getArchiveDonations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(siteContentService.getArchiveDonations(page, size),
                "Archive donations fetched");
    }

    @PatchMapping("/donations/financial/{id}/status")
    public ApiResponse<FinancialDonationResponse> updateFinancialDonationStatus(
            @PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        return ApiResponse.success(siteContentService.updateFinancialStatus(id, request),
                "Financial donation status updated");
    }

    @PatchMapping("/donations/archive/{id}/status")
    public ApiResponse<ArchiveDonationResponse> updateArchiveDonationStatus(
            @PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        return ApiResponse.success(siteContentService.updateArchiveStatus(id, request),
                "Archive donation status updated");
    }

    @GetMapping("/sitemap")
    public ApiResponse<SitemapResponse> getSitemap(
            @RequestParam(defaultValue = "ckb") String locale) {
        return ApiResponse.success(sitemapService.generate(locale), "Sitemap generated");
    }
}
