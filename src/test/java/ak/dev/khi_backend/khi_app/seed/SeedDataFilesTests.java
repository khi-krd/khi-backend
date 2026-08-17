package ak.dev.khi_backend.khi_app.seed;

import ak.dev.khi_backend.khi_app.dto.about.AboutDTOs.AboutRequest;
import ak.dev.khi_backend.khi_app.dto.service.ServiceDTOs.MediaItem;
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

    /** Both About sets are seeded together, so their slugs share one namespace. */
    private static final List<Path> ABOUT_FILES = List.of(
            Path.of("scripts/seed-data/about.json"),
            Path.of("scripts/seed-data/about-detailed.json"));
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
    void aboutSeedFilesDeserializeIntoValidRequests() throws Exception {
        List<AboutRequest> pages = new java.util.ArrayList<>();
        for (Path file : ABOUT_FILES) {
            pages.addAll(readAll(file, AboutRequest[].class));
        }

        assertThat(pages).hasSize(10);   // 3 short + 7 detailed

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

        // Slug uniqueness is enforced across BOTH files, and across languages: the DB has a
        // unique index per column, and getBySlug() matches slugCkb OR slugKmr. A collision
        // would surface as a 400 halfway through a seed run.
        assertThat(distinct(pages, AboutRequest::getSlugCkb)).isEqualTo(pages.size());
        assertThat(distinct(pages, AboutRequest::getSlugKmr)).isEqualTo(pages.size());
        assertThat(pages.stream()
                .flatMap(p -> java.util.stream.Stream.of(p.getSlugCkb(), p.getSlugKmr()))
                .collect(Collectors.toSet()))
                .as("every slug, both languages, must be globally unique")
                .hasSize(pages.size() * 2);
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

    @Test
    void seedContentEmbedsRealMediaOnly() throws Exception {
        List<AboutRequest> pages = new java.util.ArrayList<>();
        for (Path file : ABOUT_FILES) {
            pages.addAll(readAll(file, AboutRequest[].class));
        }

        for (AboutRequest page : pages) {
            // Media lives inline in the Tiptap body — every page carries at least one picture
            // in BOTH languages, otherwise one locale renders as a wall of text.
            assertThat(page.getCkbContent().getBody()).contains("<img src=\"https://");
            assertThat(page.getKmrContent().getBody()).contains("<img src=\"https://");
            if (page.getHeroVideoUrl() != null) {
                assertThat(page.getHeroPosterUrl())
                        .as("a hero video needs a poster frame, or the slot renders black")
                        .isNotBlank();
            }
        }

        List<ServiceRequest> services = readAll(SERVICES_FILE, ServiceRequest[].class);
        for (ServiceRequest service : services) {
            assertThat(service.getGalleryMedia()).as("galleryMedia").isNotEmpty();
            for (MediaItem slot : service.getGalleryMedia()) {
                assertThat(slot.getType()).isIn("IMAGE", "VIDEO");
                assertThat(slot.getUrl()).isNotBlank();
                if ("VIDEO".equals(slot.getType())) {
                    // SiteContentService.serviceSlideImage() takes a video slot's poster as the
                    // featured picture; without one the slot contributes no image at all.
                    assertThat(slot.getPosterUrl()).as("VIDEO slot poster").isNotBlank();
                }
            }
            assertThat(service.getThumbnailUrls()).isNotEmpty();
            if (service.getHeroVideoUrl() != null) {
                assertThat(service.getHeroPosterUrl()).isNotBlank();
            }
            for (ServiceContentRequest content : service.getContents()) {
                assertThat(content.getDescription()).containsPattern("<(img|video) src=\"https://");
            }
        }

        // Nothing may point anywhere but the project's own S3 bucket: no http://, no
        // localhost, no hotlinked third-party image that can vanish or get blocked.
        for (Path file : List.of(ABOUT_FILES.get(0), ABOUT_FILES.get(1), SERVICES_FILE)) {
            String raw = Files.readString(file);
            assertThat(raw).as("%s must not contain plain-http URLs", file)
                    .doesNotContain("http://");
            java.util.regex.Matcher hosts = java.util.regex.Pattern
                    .compile("https://([^/\"]+)/").matcher(raw);
            while (hosts.find()) {
                assertThat(hosts.group(1))
                        .as("unexpected media host in %s", file)
                        .isEqualTo("s3-khiwebsite.s3.us-east-1.amazonaws.com");
            }
        }
    }

    private <T> List<T> readAll(Path path, Class<T[]> arrayType) throws Exception {
        assertThat(path).as("seed file %s", path).exists();
        return List.of(MAPPER.readValue(Files.readString(path), arrayType));
    }

    private <T> long distinct(List<T> items, java.util.function.Function<T, String> key) {
        return items.stream().map(key).collect(Collectors.toSet()).size();
    }
}
