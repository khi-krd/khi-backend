package ak.dev.khi_backend.khi_app.model.site;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "donation_settings")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DonationSettings {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "title_ckb", length = 500) private String titleCkb;
    @Column(name = "title_kmr", length = 500) private String titleKmr;
    @Column(name = "description_ckb", columnDefinition = "TEXT") private String descriptionCkb;
    @Column(name = "description_kmr", columnDefinition = "TEXT") private String descriptionKmr;
    @Column(name = "hero_image_url", columnDefinition = "TEXT") private String heroImageUrl;
    @Column(name = "bank_name", length = 300) private String bankName;
    @Column(name = "account_name", length = 300) private String accountName;
    @Column(name = "account_number", length = 120) private String accountNumber;
    @Column(length = 120) private String iban;
    @Column(name = "swift_code", length = 60) private String swiftCode;
    @Column(name = "payment_instructions_ckb", columnDefinition = "TEXT") private String paymentInstructionsCkb;
    @Column(name = "payment_instructions_kmr", columnDefinition = "TEXT") private String paymentInstructionsKmr;
    @Builder.Default @Column(name = "financial_enabled") private boolean financialDonationsEnabled = true;
    @Builder.Default @Column(name = "archive_enabled") private boolean archiveDonationsEnabled = true;

    // ─── Featured (homepage carousel) ─────────────────────────────────────────
    //
    //  Donation is a singleton settings row, so there is no "which record" to
    //  pick — the flag simply publishes one donation slide that links to the
    //  donation page. Title / description reuse the page copy above; the slide
    //  image falls back to heroImageUrl when featureImageUrl is blank.

    @Builder.Default @Column(name = "featured") private boolean featured = false;
    @Column(name = "featured_order") private Integer featuredOrder;
    /** Wide picture for the homepage hero; falls back to {@link #heroImageUrl}. */
    @Column(name = "feature_image_url", columnDefinition = "TEXT") private String featureImageUrl;
}
