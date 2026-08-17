package ak.dev.khi_backend.khi_app.seed;

import ak.dev.khi_backend.khi_app.dto.about.AboutDTOs.AboutRequest;
import ak.dev.khi_backend.khi_app.dto.service.ServiceDTOs.ServiceContentRequest;
import ak.dev.khi_backend.khi_app.dto.service.ServiceDTOs.ServiceRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Guards the Kurdish Heritage Institute seed payloads in {@code scripts/seed-data/}.
 *
 * <p>They are posted to the live API by {@code scripts/seed-about-services.sh}, so the
 * risk is a field name or value that the DTOs no longer accept — this test deserializes
 * them with a STRICT mapper (unknown properties fail) and re-checks the same rules the
 * service layer enforces, so a rename breaks here instead of mid-seed.</p>
 */
class SeedDataFilesTests {

    /** Strict on purpose — a typo'd or removed field must fail, not be silently dropped. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path ABOUT_FILE    = Path.of("scripts/seed-data/about.json");
    private static final Path SERVICES_FILE = Path.of("scripts/seed-data/services.json");

    private static final DateTimeFormatter PUBLISHED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> ALLOWED_LAYOUT_TYPES =
            Set.of("MEDIA_HERO", "FEATURE_GRID", "DEFAULT");
    /** ServiceService.NAV_ANCHOR_PATTERN. */
    private static final String NAV_ANCHOR_REGEX = "^[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*$";
    /** ServiceContent.feature_description column length. */
    private static final int FEATURE_DESCRIPTION_MAX = 1000;

    @Test
    void aboutSeedFileDeserializesIntoValidRequests() throws Exception {
        List<AboutRequest> pages = readAll(ABOUT_FILE, AboutRequest[].class);

        assertThat(pages).hasSize(3);

        for (AboutRequest page : pages) {
            // AboutService.validateSlugs: CKB required, KMR unique-and-different when present
            assertThat(page.getSlugCkb()).as("slugCkb").isNotBlank();
            assertThat(page.getSlugKmr()).as("slugKmr").isNotBlank();
            assertThat(page.getSlugKmr()).isNotEqualTo(page.getSlugCkb());
            assertThat(page.getSlugCkb()).matches("^[a-z0-9-]+$");
            assertThat(page.getSlugKmr()).matches("^[a-z0-9-]+$");

            // AboutService.validateContent: at least one localized title. Both, here.
            assertThat(page.getCkbContent()).isNotNull();
            assertThat(page.getKmrContent()).isNotNull();
            assertThat(page.getCkbContent().getTitle()).isNotBlank();
            assertThat(page.getKmrContent().getTitle()).isNotBlank();
            assertThat(page.getCkbContent().getBody()).contains("<p>");
            assertThat(page.getKmrContent().getBody()).contains("<p>");
            assertThat(page.getCkbContent().getMetaDescription()).isNotBlank();
            assertThat(page.getKmrContent().getMetaDescription()).isNotBlank();

            // stats are bilingual or absent — never half-translated
            if (page.getStats() != null) {
                page.getStats().forEach(stat -> {
                    assertThat(stat.getLabelCkb()).isNotBlank();
                    assertThat(stat.getLabelKmr()).isNotBlank();
                    assertThat(stat.getValue()).isNotBlank();
                });
            }
        }

        assertThat(distinct(pages, AboutRequest::getSlugCkb)).isEqualTo(pages.size());
        assertThat(distinct(pages, AboutRequest::getSlugKmr)).isEqualTo(pages.size());
    }

    @Test
    void serviceSeedFileDeserializesIntoValidRequests() throws Exception {
        List<ServiceRequest> services = readAll(SERVICES_FILE, ServiceRequest[].class);

        assertThat(services).hasSize(8);

        for (ServiceRequest service : services) {
            assertThat(service.getServiceType()).as("serviceType").isNotBlank();
            assertThat(service.getLayoutType()).isIn(ALLOWED_LAYOUT_TYPES.toArray());
            assertThat(service.getNavAnchorId()).matches(NAV_ANCHOR_REGEX);
            assertThat(service.getSortOrder()).isNotNull();

            String publishedAt = service.getPublishedAt();
            assertThatCode(() -> LocalDateTime.parse(publishedAt, PUBLISHED_AT))
                    .as("publishedAt %s must match yyyy-MM-dd HH:mm:ss", publishedAt)
                    .doesNotThrowAnyException();

            // ServiceService.validateContents: one row per language, title required
            List<String> codes = service.getContents().stream()
                    .map(ServiceContentRequest::getLanguageCode).toList();
            assertThat(codes).containsExactlyInAnyOrder("CKB", "KMR");

            for (ServiceContentRequest content : service.getContents()) {
                assertThat(content.getTitle()).as("title").isNotBlank();
                assertThat(content.getDescription()).contains("<p>");

                // The carousel line is rendered as plain text and clamped to the column
                String feature = content.getFeatureDescription();
                assertThat(feature).as("featureDescription").isNotBlank();
                assertThat(feature).doesNotContain("<").doesNotContain(">");
                assertThat(feature.length()).isLessThanOrEqualTo(FEATURE_DESCRIPTION_MAX);
            }
        }

        assertThat(distinct(services, ServiceRequest::getNavAnchorId)).isEqualTo(services.size());
    }

    private <T> List<T> readAll(Path path, Class<T[]> arrayType) throws Exception {
        assertThat(path).as("seed file %s", path).exists();
        return List.of(MAPPER.readValue(Files.readString(path), arrayType));
    }

    private <T> long distinct(List<T> items, java.util.function.Function<T, String> key) {
        return items.stream().map(key).collect(Collectors.toSet()).size();
    }
}
