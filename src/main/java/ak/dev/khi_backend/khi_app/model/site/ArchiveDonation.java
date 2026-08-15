package ak.dev.khi_backend.khi_app.model.site;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
//this is for site settings
@Entity
@Table(name = "archive_donations", indexes = {
        @Index(name = "idx_archive_donation_status_created", columnList = "status,created_at")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ArchiveDonation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "donor_name", nullable = false, length = 200) private String donorName;
    @Column(nullable = false, length = 254) private String email;
    @Column(length = 60) private String phone;
    @Column(name = "material_type", nullable = false, length = 120) private String materialType;
    @Column(nullable = false, length = 500) private String title;
    @Column(nullable = false, columnDefinition = "TEXT") private String description;
    @Column(name = "estimated_date", length = 80) private String estimatedDate;
    @Column(name = "attachment_url", columnDefinition = "TEXT") private String attachmentUrl;
    @Builder.Default @Column(nullable = false, length = 30) private String status = "PENDING";
    @CreationTimestamp @Column(name = "created_at", updatable = false) private LocalDateTime createdAt;
}
