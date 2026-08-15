package ak.dev.khi_backend.khi_app.dto.site;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Public-site DTOs for content that is not part of the publication modules.
 */
public final class SiteContentDtos {

    private SiteContentDtos() {}

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ImageDto {
        private String url;
        private String alt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FeaturedRequest {
        @NotBlank private String type;
        @NotBlank private String slug;
        @NotBlank private String title;
        @NotBlank private String description;
        @NotBlank private String imageUrl;
        private String imageAlt;
        private String locale;
        private Integer displayOrder;
        private Boolean active;

        // NEW — used by SiteContentService.setNewsFeatured() / setProjectFeatured() / etc.
        private Boolean featured;      // null/omitted -> treated as true
        private Integer featuredOrder; // lower shows first; null sorts last

        // Hero picture for the homepage carousel, set from the dashboard's featured screen.
        // null/omitted -> leave whatever is stored alone (so plain feature/unfeature calls
        // keep working); "" -> clear it and fall back to the cover.
        private String featureImageUrl;
    }

    // NEW — used by the six setXFeatured() methods in SiteContentService and the matching
    // admin PATCH endpoints in FeaturedController. Named FeatureToggleRequest (not
    // FeaturedRequest) since that name was already taken by the class above.
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FeatureToggleRequest {
        private Boolean featured;      // null/omitted -> treated as true
        private Integer featuredOrder; // lower shows first; null sorts last
    }

    @Data @Builder(toBuilder = true) @NoArgsConstructor @AllArgsConstructor
    public static class FeaturedResponse {
        private String id;
        private String source;         // NEW: news | project | writing | video | sound-track | image-collection
        private Long entityId;         // NEW: raw id of the record in its source table
        private String type;
        private String slug;
        private String title;
        private String description;
        private ImageDto image;
        private String locale;
        private Boolean featured;      // NEW
        private Integer featuredOrder; // NEW
        private Integer displayOrder;
        private Boolean active;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SiteSettingsRequest {
        @NotNull
        @Min(1)
        @Max(20)
        private Integer maxFeaturedSlides;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SiteSettingsResponse {
        private Long id;
        private Integer maxFeaturedSlides;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TeamMemberRequest {
        @NotBlank private String nameCkb;
        private String nameKmr;
        @NotBlank private String roleCkb;
        private String roleKmr;
        private String bioCkb;
        private String bioKmr;
        private String office;
        private String imageUrl;
        private Integer displayOrder;
        private Boolean active;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TeamMemberResponse {
        private Long id;
        private String nameCkb;
        private String nameKmr;
        private String roleCkb;
        private String roleKmr;
        private String bioCkb;
        private String bioKmr;
        private String office;
        private String imageUrl;
        private Integer displayOrder;
        private Boolean active;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PartnerRequest {
        @NotBlank private String nameCkb;
        private String nameKmr;
        private String descriptionCkb;
        private String descriptionKmr;
        private String logoUrl;
        private String websiteUrl;
        private Integer displayOrder;
        private Boolean active;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PartnerResponse {
        private Long id;
        private String nameCkb;
        private String nameKmr;
        private String descriptionCkb;
        private String descriptionKmr;
        private String logoUrl;
        private String websiteUrl;
        private Integer displayOrder;
        private Boolean active;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ContactMessageRequest {
        @NotBlank @Size(max = 200) private String name;
        @NotBlank @Email @Size(max = 254) private String email;
        @Size(max = 60) private String phone;
        @NotBlank @Size(max = 300) private String subject;
        @NotBlank @Size(max = 10000) private String message;
        @Size(max = 10) private String locale;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ContactMessageResponse {
        private Long id;
        private String name;
        private String email;
        private String phone;
        private String subject;
        private String message;
        private String locale;
        private String status;
        private LocalDateTime createdAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SocialLinkRequest {
        @NotBlank @Size(max = 60) private String platform;
        @NotBlank @Size(max = 2000) private String url;
        private String labelCkb;
        private String labelKmr;
        private Integer displayOrder;
        private Boolean active;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SocialLinkResponse {
        private Long id;
        private String platform;
        private String url;
        private String labelCkb;
        private String labelKmr;
        private Integer displayOrder;
        private Boolean active;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DonationSettingsRequest {
        private String titleCkb;
        private String titleKmr;
        private String descriptionCkb;
        private String descriptionKmr;
        private String heroImageUrl;
        private String bankName;
        private String accountName;
        private String accountNumber;
        private String iban;
        private String swiftCode;
        private String paymentInstructionsCkb;
        private String paymentInstructionsKmr;
        private Boolean financialDonationsEnabled;
        private Boolean archiveDonationsEnabled;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DonationSettingsResponse {
        private Long id;
        private String titleCkb;
        private String titleKmr;
        private String descriptionCkb;
        private String descriptionKmr;
        private String heroImageUrl;
        private String bankName;
        private String accountName;
        private String accountNumber;
        private String iban;
        private String swiftCode;
        private String paymentInstructionsCkb;
        private String paymentInstructionsKmr;
        private Boolean financialDonationsEnabled;
        private Boolean archiveDonationsEnabled;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DonationTypeResponse {
        private String code;
        private String titleCkb;
        private String titleKmr;
        private Boolean enabled;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FinancialDonationRequest {
        @NotBlank private String donorName;
        /** Optional — the site submits an empty string today. {@code @Email} allows null/blank. */
        @Email private String email;
        private String phone;
        @NotNull @DecimalMin("0.01") private BigDecimal amount;
        @NotBlank private String currency;
        @NotBlank private String paymentMethod;
        private String transactionReference;
        @Size(max = 5000) private String message;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FinancialDonationResponse {
        private Long id;
        private String donorName;
        private String email;
        private String phone;
        private BigDecimal amount;
        private String currency;
        private String paymentMethod;
        private String transactionReference;
        private String message;
        private String status;
        private LocalDateTime createdAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ArchiveDonationRequest {
        @NotBlank private String donorName;
        /** Optional — the site submits an empty string today. {@code @Email} allows null/blank. */
        @Email private String email;
        private String phone;
        @NotBlank private String materialType;
        /** Optional display/credit name ("Register name" on the form). */
        @Size(max = 500) private String title;
        /** Optional free-text note / brief history. */
        @Size(max = 10000) private String description;
        private String estimatedDate;
        private String attachmentUrl;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ArchiveDonationResponse {
        private Long id;
        private String donorName;
        private String email;
        private String phone;
        private String materialType;
        private String title;
        private String description;
        private String estimatedDate;
        private String attachmentUrl;
        private String status;
        private LocalDateTime createdAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StatusRequest {
        @NotBlank private String status;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SitemapResponse {
        private String locale;
        private List<String> paths;
    }
}
