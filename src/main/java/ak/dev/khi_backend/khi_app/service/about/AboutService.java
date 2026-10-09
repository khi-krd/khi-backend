package ak.dev.khi_backend.khi_app.service.about;

import ak.dev.khi_backend.khi_app.dto.about.AboutDTOs.*;
import ak.dev.khi_backend.khi_app.model.about.About;
import ak.dev.khi_backend.khi_app.model.about.AboutContent;
import ak.dev.khi_backend.khi_app.model.about.StatItem;
import ak.dev.khi_backend.khi_app.repository.about.AboutRepository;
import ak.dev.khi_backend.khi_app.service.S3Service;
import ak.dev.khi_backend.khi_app.service.media.TiptapHtmlProcessor;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AboutService {

    private final AboutRepository aboutRepository;
    private final TiptapHtmlProcessor tiptapHtmlProcessor;
    private final S3Service s3Service;

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ============================================================
    // READ
    // ============================================================

    @Transactional(readOnly = true)
    public Page<AboutResponse> getAllActive(int page, int size) {
        return aboutRepository
                .findAllByActiveTrueOrderByDisplayOrderAsc(PageRequest.of(page, size))
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public AboutResponse getBySlug(String slug) {
        About about = aboutRepository.findBySlugCkbOrSlugKmr(slug, slug)
                .orElseThrow(() ->
                        new EntityNotFoundException("About page not found: " + slug));
        return toResponse(about);
    }

    @Transactional(readOnly = true)
    public AboutResponse getByIdentifier(String identifier) {
        try {
            return toResponse(aboutRepository.findById(Long.valueOf(identifier))
                    .orElseThrow(() -> new EntityNotFoundException(
                            "About page not found: " + identifier)));
        } catch (NumberFormatException ignored) {
            return getBySlug(identifier);
        }
    }

    // ============================================================
    // CREATE
    // ============================================================

    @Transactional
    public AboutResponse create(AboutRequest request) {
        if (request == null) request = new AboutRequest();

        About about = new About();
        about.setSlugCkb(resolveCkbSlug(request.getSlugCkb(), null, null));
        about.setSlugKmr(resolveKmrSlug(request.getSlugKmr(), about.getSlugCkb(), null, null));

        about.setCkbContent(buildAboutContent(request.getCkbContent()));
        about.setKmrContent(buildAboutContent(request.getKmrContent()));
        about.setStats(buildStats(request.getStats()));
        applyInstitutionalMedia(about, request);
        about.setActive(request.getActive() == null || request.getActive());
        about.setDisplayOrder(request.getDisplayOrder() == null ? 0 : request.getDisplayOrder());

        return toResponse(aboutRepository.save(about));
    }

    // ============================================================
    // UPDATE
    // ============================================================

    @Transactional
    public AboutResponse update(Long id, AboutRequest request) {
        if (request == null) request = new AboutRequest();

        About about = aboutRepository.findById(id)
                .orElseThrow(() ->
                        new EntityNotFoundException("About not found: " + id));

        about.setSlugCkb(resolveCkbSlug(request.getSlugCkb(), about.getSlugCkb(), id));
        about.setSlugKmr(resolveKmrSlug(request.getSlugKmr(), about.getSlugCkb(), about.getSlugKmr(), id));
        about.setCkbContent(buildAboutContent(request.getCkbContent()));
        about.setKmrContent(buildAboutContent(request.getKmrContent()));
        about.setStats(buildStats(request.getStats()));
        applyInstitutionalMedia(about, request);
        if (request.getActive() != null) about.setActive(request.getActive());
        if (request.getDisplayOrder() != null) about.setDisplayOrder(request.getDisplayOrder());

        return toResponse(aboutRepository.save(about));
    }

    // ============================================================
    // DELETE
    // ============================================================

    @Transactional
    public void delete(Long id) {
        About about = aboutRepository.findById(id)
                .orElseThrow(() ->
                        new EntityNotFoundException("About not found: " + id));

        aboutRepository.delete(about);
        s3Service.deleteFiles(
                about.getFounderImageUrl(),
                about.getHeroVideoUrl(),
                about.getHeroPosterUrl(),
                about.getFeatureImageUrl());
        log.info("Deleted about page id={}", id);
    }

    // ============================================================
    // PRIVATE HELPERS
    // ============================================================

    /**
     * All fields optional. A missing/duplicate slug falls back to a generated
     * unique one; on update a blank slug keeps the existing value. Only an
     * explicitly colliding slug gets nudged unique with a suffix.
     */
    private String resolveCkbSlug(String requested, String existing, Long selfId) {
        String slug = (requested == null || requested.isBlank())
                ? existing
                : requested.trim();
        if (slug == null || slug.isBlank()) {
            slug = "page";
        }
        return uniqueSlug(slug, selfId, true);
    }

    private String resolveKmrSlug(String requested, String ckbSlug, String existing, Long selfId) {
        String slug = (requested == null || requested.isBlank())
                ? existing
                : requested.trim();
        if (slug == null || slug.isBlank()) {
            return null;
        }
        if (slug.equals(ckbSlug)) {
            slug = slug + "-kmr";
        }
        return uniqueSlug(slug, selfId, false);
    }

    private String uniqueSlug(String slug, Long selfId, boolean ckb) {
        String candidate = slug;
        int n = 2;
        while (slugTaken(candidate, selfId, ckb)) {
            candidate = slug + "-" + n++;
        }
        return candidate;
    }

    private boolean slugTaken(String slug, Long selfId, boolean ckb) {
        var found = ckb
                ? aboutRepository.findBySlugCkb(slug)
                : aboutRepository.findBySlugKmr(slug);
        return found.isPresent()
                && (selfId == null || !found.get().getId().equals(selfId));
    }

    private AboutContent buildAboutContent(AboutContentRequest req) {
        if (req == null) return new AboutContent();
        return AboutContent.builder()
                .title(req.getTitle())
                .subtitle(req.getSubtitle())
                .metaDescription(req.getMetaDescription())
                .body(tiptapHtmlProcessor.process(req.getBody()))
                .build();
    }

    private List<StatItem> buildStats(List<StatItemDto> stats) {
        if (stats == null || stats.isEmpty()) return new ArrayList<>();
        return stats.stream()
                .filter(s -> s != null
                        && (notBlank(s.getValue()) || notBlank(s.getLabelCkb()) || notBlank(s.getLabelKmr())))
                .map(s -> StatItem.builder()
                        .labelCkb(s.getLabelCkb())
                        .labelKmr(s.getLabelKmr())
                        .value(s.getValue())
                        .build())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    // ─── Response Mappers ─────────────────────────────────────────────────────

    private AboutResponse toResponse(About about) {
        return AboutResponse.builder()
                .id(about.getId())
                .slugCkb(about.getSlugCkb())
                .slugKmr(about.getSlugKmr())
                .ckbContent(toContentResponse(about.getCkbContent()))
                .kmrContent(toContentResponse(about.getKmrContent()))
                .active(about.isActive())
                .stats(toStatsResponse(about.getStats()))
                .founderNameCkb(about.getFounderNameCkb())
                .founderNameKmr(about.getFounderNameKmr())
                .founderBioCkb(about.getFounderBioCkb())
                .founderBioKmr(about.getFounderBioKmr())
                .founderImageUrl(about.getFounderImageUrl())
                .heroVideoUrl(about.getHeroVideoUrl())
                .heroPosterUrl(about.getHeroPosterUrl())
                .displayOrder(about.getDisplayOrder())
                .featured(about.isFeatured())
                .featuredOrder(about.getFeaturedOrder())
                .featureImageUrl(about.getFeatureImageUrl())
                .createdAt(about.getCreatedAt() != null
                        ? about.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(about.getUpdatedAt() != null
                        ? about.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }

    private void applyInstitutionalMedia(About about, AboutRequest request) {
        about.setFounderNameCkb(blankToNull(request.getFounderNameCkb()));
        about.setFounderNameKmr(blankToNull(request.getFounderNameKmr()));
        about.setFounderBioCkb(blankToNull(request.getFounderBioCkb()));
        about.setFounderBioKmr(blankToNull(request.getFounderBioKmr()));
        about.setFounderImageUrl(blankToNull(request.getFounderImageUrl()));
        about.setHeroVideoUrl(blankToNull(request.getHeroVideoUrl()));
        about.setHeroPosterUrl(blankToNull(request.getHeroPosterUrl()));
    }

    private AboutContentResponse toContentResponse(AboutContent content) {
        if (content == null) return null;
        return AboutContentResponse.builder()
                .title(content.getTitle())
                .subtitle(content.getSubtitle())
                .metaDescription(content.getMetaDescription())
                .body(content.getBody())
                .build();
    }

    private List<StatItemDto> toStatsResponse(List<StatItem> stats) {
        if (stats == null || stats.isEmpty()) return List.of();
        return stats.stream()
                .filter(java.util.Objects::nonNull)
                .map(s -> StatItemDto.builder()
                        .labelCkb(s.getLabelCkb())
                        .labelKmr(s.getLabelKmr())
                        .value(s.getValue())
                        .build())
                .collect(Collectors.toList());
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
