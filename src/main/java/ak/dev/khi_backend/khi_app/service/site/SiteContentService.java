package ak.dev.khi_backend.khi_app.service.site;

import ak.dev.khi_backend.khi_app.dto.site.SiteContentDtos.*;
import ak.dev.khi_backend.khi_app.enums.MediaKind;
import ak.dev.khi_backend.khi_app.model.about.About;
import ak.dev.khi_backend.khi_app.model.about.AboutContent;
import ak.dev.khi_backend.khi_app.model.news.News;
import ak.dev.khi_backend.khi_app.model.project.Project;
import ak.dev.khi_backend.khi_app.model.publishment.image.ImageCollection;
import ak.dev.khi_backend.khi_app.model.publishment.sound.SoundTrack;
import ak.dev.khi_backend.khi_app.model.publishment.video.Video;
import ak.dev.khi_backend.khi_app.model.publishment.writing.Writing;
import ak.dev.khi_backend.khi_app.model.service.ServiceContent;
import ak.dev.khi_backend.khi_app.model.service.ServiceMedia;
import ak.dev.khi_backend.khi_app.model.site.*;
import ak.dev.khi_backend.khi_app.repository.about.AboutRepository;
import ak.dev.khi_backend.khi_app.repository.news.NewsRepository;
import ak.dev.khi_backend.khi_app.repository.project.ProjectRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.image.ImageCollectionRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.sound.SoundTrackRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.video.VideoRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.writing.WritingRepository;
import ak.dev.khi_backend.khi_app.repository.service.ServiceRepository;
import ak.dev.khi_backend.khi_app.repository.site.*;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class SiteContentService {

    private static final Set<String> SUBMISSION_STATUSES =
            Set.of("NEW", "PENDING", "IN_REVIEW", "APPROVED", "COMPLETED", "REJECTED", "CLOSED");

    /** Allowed archive material types (spec §3). */
    private static final Set<String> ARCHIVE_MATERIAL_TYPES =
            Set.of("PHOTOGRAPH", "MANUSCRIPT", "DOCUMENT", "AUDIO", "VIDEO", "OTHER");

    /** Allowed financial donation currencies (spec §5). */
    private static final Set<String> DONATION_CURRENCIES = Set.of("IQD", "USD");

    /** Tiptap-HTML -> plain-text excerpt, used as the Service slide description fallback. */
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final int FEATURED_EXCERPT_MAX_CHARS = 300;

    private final TeamMemberRepository teamRepository;
    private final PartnerRepository partnerRepository;
    private final ContactMessageRepository contactMessageRepository;
    private final SocialLinkRepository socialLinkRepository;
    private final DonationSettingsRepository donationSettingsRepository;
    private final FinancialDonationRepository financialDonationRepository;
    private final ArchiveDonationRepository archiveDonationRepository;
    private final SiteSettingsRepository siteSettingsRepository;
    private final NewsRepository newsRepository;
    private final ProjectRepository projectRepository;
    private final WritingRepository writingRepository;
    private final VideoRepository videoRepository;
    private final SoundTrackRepository soundTrackRepository;
    private final ImageCollectionRepository imageCollectionRepository;
    private final AboutRepository aboutRepository;
    private final ServiceRepository serviceRepository;

    // =========================================================================================
    // Featured
    //
    // Featured slides are curated, not automatic. An admin explicitly flags individual News /
    // Project / Writing / Video / SoundTrack / ImageCollection / About / Service records as
    // featured (featured = true) and optionally sets featuredOrder to control sequence, plus the
    // singleton Donation page. getFeatured() collects every flagged record across all nine
    // sources, sorts them globally by featuredOrder (ties broken by newest id first), and
    // renumbers displayOrder 1..N on the way out.
    //
    // The total number of featured slides across ALL sources combined is capped by
    // SiteSettings.maxFeaturedSlides (default 5, changeable by admin via updateSiteSettings()).
    //
    // ─── Where each slide's title / description / image comes from ─────────────────────────
    //  The six publication types derive title + description from their bilingual content and
    //  use featureImageUrl only as an override on top of their cover. The three institutional
    //  sources have no cover and no carousel-ready copy, so they resolve differently:
    //
    //    about     title = content title, description = subtitle ?? metaDescription,
    //              image = featureImageUrl (REQUIRED — About owns no cover of any kind)
    //    service   title = ServiceContent.title, description = ServiceContent.featureDescription
    //              ?? stripped excerpt of the Tiptap description,
    //              image = featureImageUrl ?? first gallery image ?? video poster
    //    donation  title/description = the donation page copy,
    //              image = featureImageUrl ?? heroImageUrl
    // =========================================================================================

    @Transactional(readOnly = true)
    public List<FeaturedResponse> getFeatured(String locale) {
        String resolvedLocale = resolveFeaturedLocale(locale);
        boolean kmr = "kmr".equals(resolvedLocale);

        List<FeaturedCandidate> candidates = new ArrayList<>();

        newsRepository.findByFeaturedTrueOrderByFeaturedOrderAscIdDesc().forEach(news ->
                addCandidate(candidates, newsFeatured(news, resolvedLocale, kmr),
                        news.getFeaturedOrder(), news.getId()));

        projectRepository.findByFeaturedTrueOrderByFeaturedOrderAscIdDesc().forEach(project ->
                addCandidate(candidates, projectFeatured(project, resolvedLocale, kmr),
                        project.getFeaturedOrder(), project.getId()));

        writingRepository.findFeaturedWithTopic().forEach(writing ->
                addCandidate(candidates, writingFeatured(writing, resolvedLocale, kmr),
                        writing.getFeaturedOrder(), writing.getId()));

        videoRepository.findFeaturedWithTopic().forEach(video ->
                addCandidate(candidates, videoFeatured(video, resolvedLocale, kmr),
                        video.getFeaturedOrder(), video.getId()));

        soundTrackRepository.findByFeaturedTrueOrderByFeaturedOrderAscIdDesc().forEach(sound ->
                addCandidate(candidates, soundFeatured(sound, resolvedLocale, kmr),
                        sound.getFeaturedOrder(), sound.getId()));

        imageCollectionRepository.findByFeaturedTrueOrderByFeaturedOrderAscIdDesc().forEach(collection ->
                addCandidate(candidates, imageFeatured(collection, resolvedLocale, kmr),
                        collection.getFeaturedOrder(), collection.getId()));

        aboutRepository.findByFeaturedTrueOrderByFeaturedOrderAscIdDesc().forEach(about ->
                addCandidate(candidates, aboutFeatured(about, resolvedLocale, kmr),
                        about.getFeaturedOrder(), about.getId()));

        serviceRepository.findFeaturedWithContents().forEach(service ->
                addCandidate(candidates, serviceFeatured(service, resolvedLocale, kmr),
                        service.getFeaturedOrder(), service.getId()));

        featuredDonationSettings().ifPresent(settings ->
                addCandidate(candidates, donationFeatured(settings, resolvedLocale, kmr),
                        settings.getFeaturedOrder(), settings.getId()));

        candidates.sort(Comparator
                .comparing((FeaturedCandidate c) ->
                        c.featuredOrder() == null ? Integer.MAX_VALUE : c.featuredOrder())
                .thenComparing(FeaturedCandidate::id, Comparator.reverseOrder()));

        int slideCount = Math.min(candidates.size(), getMaxFeaturedSlides());
        List<FeaturedResponse> slides = new ArrayList<>(slideCount);
        int order = 1;
        for (FeaturedCandidate candidate : candidates.subList(0, slideCount)) {
            slides.add(candidate.response().toBuilder().displayOrder(order++).build());
        }
        return slides;
    }

    private record FeaturedCandidate(FeaturedResponse response, Integer featuredOrder, Long id) {}

    private void addCandidate(
            List<FeaturedCandidate> candidates, FeaturedResponse response,
            Integer featuredOrder, Long id) {
        if (response != null) {
            candidates.add(new FeaturedCandidate(response, featuredOrder, id));
        }
    }

    // --- Global featured count and limit --------------------------------------------------

    // Sums featured records across every source. Used by every set*Featured() method to enforce
    // the global cap before flagging a new record as featured. Donation counts as at most 1 —
    // it is a singleton settings row, not a collection.
    private long countAllFeatured() {
        return newsRepository.countByFeaturedTrue()
                + projectRepository.countByFeaturedTrue()
                + writingRepository.countByFeaturedTrue()
                + videoRepository.countByFeaturedTrue()
                + soundTrackRepository.countByFeaturedTrue()
                + imageCollectionRepository.countByFeaturedTrue()
                + aboutRepository.countByFeaturedTrue()
                + serviceRepository.countByFeaturedTrue()
                + (featuredDonationSettings().isPresent() ? 1 : 0);
    }

    /** The donation settings row, only when it is currently flagged as featured. */
    private Optional<DonationSettings> featuredDonationSettings() {
        return donationSettingsRepository.findAll().stream()
                .findFirst()
                .filter(DonationSettings::isFeatured);
    }

    // Reads the admin-configurable limit from SiteSettings.
    private int getMaxFeaturedSlides() {
        return siteSettingsRepository.findFirstByOrderByIdAsc()
                .map(SiteSettings::getMaxFeaturedSlides)
                .filter(limit -> limit > 0)
                .orElse(SiteSettings.DEFAULT_MAX_FEATURED_SLIDES);
    }

    // --- Site settings (admin) -----------------------------------------------------------

    @Transactional(readOnly = true)
    public SiteSettingsResponse getSiteSettings() {
        return siteSettingsRepository.findFirstByOrderByIdAsc()
                .map(s -> SiteSettingsResponse.builder()
                        .id(s.getId())
                        .maxFeaturedSlides(s.getMaxFeaturedSlides())
                        .build())
                .orElseGet(() -> SiteSettingsResponse.builder()
                        .maxFeaturedSlides(SiteSettings.DEFAULT_MAX_FEATURED_SLIDES)
                        .build());
    }

    @Transactional
    public SiteSettingsResponse updateSiteSettings(SiteSettingsRequest request) {
        SiteSettings settings = siteSettingsRepository.findFirstByOrderByIdAsc()
                .orElseGet(SiteSettings::new);
        settings.setMaxFeaturedSlides(request.getMaxFeaturedSlides());
        SiteSettings saved = siteSettingsRepository.save(settings);
        return SiteSettingsResponse.builder()
                .id(saved.getId())
                .maxFeaturedSlides(saved.getMaxFeaturedSlides())
                .build();
    }

    // --- Feature / unfeature a single record by its entity id ----------------------------
    //
    // Each method:
    //   1. Loads the entity (404 if not found).
    //   2. If the request is turning featured ON and the record is NOT already featured,
    //      checks the global count against the admin-configurable limit.
    //   3. Delegates the actual field assignment to entity.markFeatured() so the entity
    //      owns that logic rather than the service.
    //   4. Saves.
    //
    // The !entity.isFeatured() guard means updating the featuredOrder of an already-featured
    // record skips the count check — it is already included in the current total.

    @Transactional
    public void setNewsFeatured(Long id, FeaturedRequest request) {
        News news = newsRepository.findById(id)
                .orElseThrow(() -> notFound("News", id));
        boolean turningOn = request.getFeatured() == null || request.getFeatured();
        if (turningOn && !news.isFeatured() && countAllFeatured() >= getMaxFeaturedSlides()) {
            throw new IllegalStateException(
                    "Maximum of " + getMaxFeaturedSlides()
                            + " featured slides allowed across all content. Unfeature one first.");
        }
        news.setFeatured(turningOn);
        news.setFeaturedOrder(turningOn ? request.getFeaturedOrder() : null);
        if (request.getFeatureImageUrl() != null) {
            news.setFeatureImageUrl(trimToNull(request.getFeatureImageUrl()));
        }
        newsRepository.save(news);
    }

    @Transactional
    public void setProjectFeatured(Long id, FeaturedRequest request) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> notFound("Project", id));
        boolean turningOn = request.getFeatured() == null || request.getFeatured();
        if (turningOn && !project.isFeatured() && countAllFeatured() >= getMaxFeaturedSlides()) {
            throw new IllegalStateException(
                    "Maximum of " + getMaxFeaturedSlides()
                            + " featured slides allowed across all content. Unfeature one first.");
        }
        project.setFeatured(turningOn);
        project.setFeaturedOrder(turningOn ? request.getFeaturedOrder() : null);
        if (request.getFeatureImageUrl() != null) {
            project.setFeatureImageUrl(trimToNull(request.getFeatureImageUrl()));
        }
        projectRepository.save(project);
    }

    @Transactional
    public void setWritingFeatured(Long id, FeaturedRequest request) {
        Writing writing = writingRepository.findById(id)
                .orElseThrow(() -> notFound("Writing", id));
        boolean turningOn = request.getFeatured() == null || request.getFeatured();
        if (turningOn && !writing.isFeatured() && countAllFeatured() >= getMaxFeaturedSlides()) {
            throw new IllegalStateException(
                    "Maximum of " + getMaxFeaturedSlides()
                            + " featured slides allowed across all content. Unfeature one first.");
        }
        writing.setFeatured(turningOn);
        writing.setFeaturedOrder(turningOn ? request.getFeaturedOrder() : null);
        if (request.getFeatureImageUrl() != null) {
            writing.setFeatureImageUrl(trimToNull(request.getFeatureImageUrl()));
        }
        writingRepository.save(writing);
    }

    @Transactional
    public void setVideoFeatured(Long id, FeaturedRequest request) {
        Video video = videoRepository.findById(id)
                .orElseThrow(() -> notFound("Video", id));
        boolean turningOn = request.getFeatured() == null || request.getFeatured();
        if (turningOn && !video.isFeatured() && countAllFeatured() >= getMaxFeaturedSlides()) {
            throw new IllegalStateException(
                    "Maximum of " + getMaxFeaturedSlides()
                            + " featured slides allowed across all content. Unfeature one first.");
        }
        video.setFeatured(turningOn);
        video.setFeaturedOrder(turningOn ? request.getFeaturedOrder() : null);
        if (request.getFeatureImageUrl() != null) {
            video.setFeatureImageUrl(trimToNull(request.getFeatureImageUrl()));
        }
        videoRepository.save(video);
    }

    @Transactional
    public void setSoundTrackFeatured(Long id, FeaturedRequest request) {
        SoundTrack sound = soundTrackRepository.findById(id)
                .orElseThrow(() -> notFound("Sound track", id));
        boolean turningOn = request.getFeatured() == null || request.getFeatured();
        if (turningOn && !sound.isFeatured() && countAllFeatured() >= getMaxFeaturedSlides()) {
            throw new IllegalStateException(
                    "Maximum of " + getMaxFeaturedSlides()
                            + " featured slides allowed across all content. Unfeature one first.");
        }
        sound.setFeatured(turningOn);
        sound.setFeaturedOrder(turningOn ? request.getFeaturedOrder() : null);
        if (request.getFeatureImageUrl() != null) {
            sound.setFeatureImageUrl(trimToNull(request.getFeatureImageUrl()));
        }
        soundTrackRepository.save(sound);
    }

    @Transactional
    public void setImageCollectionFeatured(Long id, FeaturedRequest request) {
        ImageCollection collection = imageCollectionRepository.findById(id)
                .orElseThrow(() -> notFound("Image collection", id));
        boolean turningOn = request.getFeatured() == null || request.getFeatured();
        if (turningOn && !collection.isFeatured() && countAllFeatured() >= getMaxFeaturedSlides()) {
            throw new IllegalStateException(
                    "Maximum of " + getMaxFeaturedSlides()
                            + " featured slides allowed across all content. Unfeature one first.");
        }
        collection.setFeatured(turningOn);
        collection.setFeaturedOrder(turningOn ? request.getFeaturedOrder() : null);
        if (request.getFeatureImageUrl() != null) {
            collection.setFeatureImageUrl(trimToNull(request.getFeatureImageUrl()));
        }
        imageCollectionRepository.save(collection);
    }

    // --- Institutional pages: About / Service / Donation ---------------------------------
    //
    // Same contract as the six above (cap check, featuredOrder cleared on unfeature), with one
    // extra guard: featuredSlide() drops any candidate whose image is blank, so a page featured
    // without a resolvable picture would vanish from the carousel with no error anywhere. These
    // three reject that up front instead.

    @Transactional
    public void setAboutFeatured(Long id, FeaturedRequest request) {
        About about = aboutRepository.findById(id)
                .orElseThrow(() -> notFound("About page", id));
        boolean turningOn = request.getFeatured() == null || request.getFeatured();
        if (turningOn && !about.isFeatured() && countAllFeatured() >= getMaxFeaturedSlides()) {
            throw new IllegalStateException(
                    "Maximum of " + getMaxFeaturedSlides()
                            + " featured slides allowed across all content. Unfeature one first.");
        }
        if (request.getFeatureImageUrl() != null) {
            about.setFeatureImageUrl(trimToNull(request.getFeatureImageUrl()));
        }
        if (turningOn && isBlank(about.getFeatureImageUrl())) {
            throw new IllegalArgumentException(
                    "featureImageUrl is required to feature an About page — About has no cover "
                            + "image to fall back on.");
        }
        about.setFeatured(turningOn);
        about.setFeaturedOrder(turningOn ? request.getFeaturedOrder() : null);
        aboutRepository.save(about);
    }

    // Evicts the "services" cache: ServiceService caches its read paths and now returns the
    // featured fields, so a toggle written from here would otherwise leave stale pages behind.
    @CacheEvict(value = "services", allEntries = true)
    @Transactional
    public void setServiceFeatured(Long id, FeaturedRequest request) {
        ak.dev.khi_backend.khi_app.model.service.Service service = serviceRepository.findById(id)
                .orElseThrow(() -> notFound("Service", id));
        boolean turningOn = request.getFeatured() == null || request.getFeatured();
        if (turningOn && !service.isFeatured() && countAllFeatured() >= getMaxFeaturedSlides()) {
            throw new IllegalStateException(
                    "Maximum of " + getMaxFeaturedSlides()
                            + " featured slides allowed across all content. Unfeature one first.");
        }
        if (request.getFeatureImageUrl() != null) {
            service.setFeatureImageUrl(trimToNull(request.getFeatureImageUrl()));
        }
        if (turningOn && isBlank(serviceSlideImage(service))) {
            throw new IllegalArgumentException(
                    "featureImageUrl is required to feature a service that has no gallery image.");
        }
        service.setFeatured(turningOn);
        service.setFeaturedOrder(turningOn ? request.getFeaturedOrder() : null);
        serviceRepository.save(service);
    }

    /**
     * Donation is a singleton settings row — there is no id to address, so this takes no id and
     * returns the saved settings so the dashboard can re-render the donation screen in one call.
     */
    @Transactional
    public DonationSettingsResponse setDonationFeatured(FeaturedRequest request) {
        DonationSettings settings = donationSettingsRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Donation settings have not been saved yet — save the donation page "
                                + "before featuring it."));
        boolean turningOn = request.getFeatured() == null || request.getFeatured();
        if (turningOn && !settings.isFeatured() && countAllFeatured() >= getMaxFeaturedSlides()) {
            throw new IllegalStateException(
                    "Maximum of " + getMaxFeaturedSlides()
                            + " featured slides allowed across all content. Unfeature one first.");
        }
        if (request.getFeatureImageUrl() != null) {
            settings.setFeatureImageUrl(trimToNull(request.getFeatureImageUrl()));
        }
        if (turningOn && isBlank(firstNonBlank(
                settings.getFeatureImageUrl(), settings.getHeroImageUrl()))) {
            throw new IllegalArgumentException(
                    "featureImageUrl or heroImageUrl is required to feature the donation page.");
        }
        settings.setFeatured(turningOn);
        settings.setFeaturedOrder(turningOn ? request.getFeaturedOrder() : null);
        return donationSettingsResponse(donationSettingsRepository.save(settings));
    }

    // --- Per-type mappers (entity -> FeaturedResponse) ------------------------------------

    private FeaturedResponse newsFeatured(News news, String locale, boolean kmr) {
        String title = localized(
                news.getCkbContent() == null ? null : news.getCkbContent().getTitle(),
                news.getKmrContent() == null ? null : news.getKmrContent().getTitle(),
                kmr);
        String description = localized(
                news.getCkbContent() == null ? null : news.getCkbContent().getDescription(),
                news.getKmrContent() == null ? null : news.getKmrContent().getDescription(),
                kmr);
        String imageUrl = news.getCoverMediaType() == null
                || news.getCoverMediaType() == MediaKind.IMAGE
                ? firstNonBlank(news.getCoverUrl(), news.getCoverThumbnailUrl())
                : news.getCoverThumbnailUrl();
        return featuredSlide(
                "news", news.getId(), "article", String.valueOf(news.getId()),
                title, description,
                firstNonBlank(news.getFeatureImageUrl(), imageUrl), locale,
                news.isFeatured(), news.getFeaturedOrder());
    }

    private FeaturedResponse projectFeatured(Project project, String locale, boolean kmr) {
        String title = localized(
                project.getCkbContent() == null ? null : project.getCkbContent().getTitle(),
                project.getKmrContent() == null ? null : project.getKmrContent().getTitle(),
                kmr);
        String description = localized(
                project.getCkbContent() == null ? null : project.getCkbContent().getDescription(),
                project.getKmrContent() == null ? null : project.getKmrContent().getDescription(),
                kmr);
        String imageUrl = project.getCoverMediaType() == null
                || project.getCoverMediaType() == MediaKind.IMAGE
                ? project.getCoverUrl()
                : firstNonBlank(project.getCoverThumbnailUrl(), project.getCoverUrl());
        return featuredSlide(
                "project", project.getId(), "archive", String.valueOf(project.getId()),
                title, description,
                firstNonBlank(project.getFeatureImageUrl(), imageUrl), locale,
                project.isFeatured(), project.getFeaturedOrder());
    }

    private FeaturedResponse writingFeatured(Writing writing, String locale, boolean kmr) {
        String title = localized(
                writing.getCkbContent() == null ? null : writing.getCkbContent().getTitle(),
                writing.getKmrContent() == null ? null : writing.getKmrContent().getTitle(),
                kmr);
        String description = localized(
                writing.getCkbContent() == null ? null : writing.getCkbContent().getDescription(),
                writing.getKmrContent() == null ? null : writing.getKmrContent().getDescription(),
                kmr);
        String imageUrl = localized(writing.getCkbCoverUrl(), writing.getKmrCoverUrl(), kmr);
        return featuredSlide(
                "writing", writing.getId(), "book", String.valueOf(writing.getId()),
                title, description,
                firstNonBlank(writing.getFeatureImageUrl(), imageUrl, writing.getHoverCoverUrl()),
                locale, writing.isFeatured(), writing.getFeaturedOrder());
    }

    private FeaturedResponse videoFeatured(Video video, String locale, boolean kmr) {
        String title = localized(
                video.getCkbContent() == null ? null : video.getCkbContent().getTitle(),
                video.getKmrContent() == null ? null : video.getKmrContent().getTitle(),
                kmr);
        String description = localized(
                video.getCkbContent() == null ? null : video.getCkbContent().getDescription(),
                video.getKmrContent() == null ? null : video.getKmrContent().getDescription(),
                kmr);
        String imageUrl = localized(video.getCkbCoverUrl(), video.getKmrCoverUrl(), kmr);
        return featuredSlide(
                "video", video.getId(), "video", String.valueOf(video.getId()),
                title, description,
                firstNonBlank(video.getFeatureImageUrl(), imageUrl, video.getHoverCoverUrl()),
                locale, video.isFeatured(), video.getFeaturedOrder());
    }

    private FeaturedResponse soundFeatured(SoundTrack sound, String locale, boolean kmr) {
        String title = localized(
                sound.getCkbContent() == null ? null : sound.getCkbContent().getTitle(),
                sound.getKmrContent() == null ? null : sound.getKmrContent().getTitle(),
                kmr);
        String description = localized(
                sound.getCkbContent() == null ? null : sound.getCkbContent().getDescription(),
                sound.getKmrContent() == null ? null : sound.getKmrContent().getDescription(),
                kmr);
        String imageUrl = localized(sound.getCkbCoverUrl(), sound.getKmrCoverUrl(), kmr);
        return featuredSlide(
                "sound-track", sound.getId(), "audio", String.valueOf(sound.getId()),
                title, description,
                firstNonBlank(sound.getFeatureImageUrl(), imageUrl, sound.getHoverCoverUrl()),
                locale, sound.isFeatured(), sound.getFeaturedOrder());
    }

    private FeaturedResponse imageFeatured(ImageCollection collection, String locale, boolean kmr) {
        String title = localized(
                collection.getCkbContent() == null ? null : collection.getCkbContent().getTitle(),
                collection.getKmrContent() == null ? null : collection.getKmrContent().getTitle(),
                kmr);
        String description = localized(
                collection.getCkbContent() == null
                        ? null : collection.getCkbContent().getDescription(),
                collection.getKmrContent() == null
                        ? null : collection.getKmrContent().getDescription(),
                kmr);
        String imageUrl = localized(
                collection.getCkbCoverUrl(), collection.getKmrCoverUrl(), kmr);
        String slug = localized(collection.getSlugCkb(), collection.getSlugKmr(), kmr);
        return featuredSlide(
                "image-collection", collection.getId(), "gallery",
                firstNonBlank(slug, String.valueOf(collection.getId())),
                title, description,
                firstNonBlank(collection.getFeatureImageUrl(), imageUrl, collection.getHoverCoverUrl()),
                locale, collection.isFeatured(), collection.getFeaturedOrder());
    }

    private FeaturedResponse aboutFeatured(About about, String locale, boolean kmr) {
        AboutContent ckb = about.getCkbContent();
        AboutContent kur = about.getKmrContent();
        String title = localized(
                ckb == null ? null : ckb.getTitle(),
                kur == null ? null : kur.getTitle(),
                kmr);
        // About has no description field — subtitle is the human-written one-liner and
        // metaDescription is the SEO summary, so either reads correctly on a slide.
        String description = localized(
                ckb == null ? null : firstNonBlank(ckb.getSubtitle(), ckb.getMetaDescription()),
                kur == null ? null : firstNonBlank(kur.getSubtitle(), kur.getMetaDescription()),
                kmr);
        String slug = firstNonBlank(
                localized(about.getSlugCkb(), about.getSlugKmr(), kmr),
                String.valueOf(about.getId()));
        return featuredSlide(
                "about", about.getId(), "about", slug,
                title, description,
                about.getFeatureImageUrl(), locale,
                about.isFeatured(), about.getFeaturedOrder());
    }

    private FeaturedResponse serviceFeatured(
            ak.dev.khi_backend.khi_app.model.service.Service service, String locale, boolean kmr) {
        ServiceContent content = serviceContentFor(service, kmr);
        String title = content == null ? null : content.getTitle();
        // ServiceContent.description is Tiptap HTML — never slide-safe. Prefer the authored
        // plain-text line and only fall back to a stripped excerpt of the HTML.
        String description = content == null ? null : firstNonBlank(
                content.getFeatureDescription(), plainTextExcerpt(content.getDescription()));
        return featuredSlide(
                "service", service.getId(), "service",
                firstNonBlank(service.getNavAnchorId(), String.valueOf(service.getId())),
                title, description,
                serviceSlideImage(service), locale,
                service.isFeatured(), service.getFeaturedOrder());
    }

    private FeaturedResponse donationFeatured(
            DonationSettings settings, String locale, boolean kmr) {
        String title = localized(settings.getTitleCkb(), settings.getTitleKmr(), kmr);
        String description =
                localized(settings.getDescriptionCkb(), settings.getDescriptionKmr(), kmr);
        return featuredSlide(
                "donation", settings.getId(), "donation", "donation",
                title, description,
                firstNonBlank(settings.getFeatureImageUrl(), settings.getHeroImageUrl()), locale,
                settings.isFeatured(), settings.getFeaturedOrder());
    }

    /**
     * The requested language's content row, falling back to the other language.
     * Service stores bilingual text as one row per languageCode rather than embedded columns.
     */
    private ServiceContent serviceContentFor(
            ak.dev.khi_backend.khi_app.model.service.Service service, boolean kmr) {
        String preferred = kmr ? "KMR" : "CKB";
        ServiceContent fallback = null;
        for (ServiceContent content : service.getContents()) {
            if (content == null || content.getLanguageCode() == null) continue;
            if (preferred.equalsIgnoreCase(content.getLanguageCode())) return content;
            if (fallback == null) fallback = content;
        }
        return fallback;
    }

    /** featureImageUrl, else the first usable gallery picture (video slots use their poster). */
    private String serviceSlideImage(
            ak.dev.khi_backend.khi_app.model.service.Service service) {
        if (!isBlank(service.getFeatureImageUrl())) {
            return service.getFeatureImageUrl().trim();
        }
        for (ServiceMedia media : service.getGalleryMedia()) {
            if (media == null) continue;
            boolean isVideo = "VIDEO".equalsIgnoreCase(media.getType());
            String url = isVideo ? media.getPosterUrl() : media.getUrl();
            if (!isBlank(url)) return url;
        }
        for (String url : service.getFeatureImageUrls()) {
            if (!isBlank(url)) return url;
        }
        return service.getHeroPosterUrl();
    }

    private FeaturedResponse featuredSlide(
            String source, Long entityId, String type, String slug,
            String title, String description, String imageUrl,
            String locale, boolean featured, Integer featuredOrder) {
        if (entityId == null || isBlank(imageUrl)) {
            return null;
        }
        String resolvedTitle = firstNonBlank(title, type + " " + entityId);
        return FeaturedResponse.builder()
                .id(source + "-" + entityId)
                .source(source)
                .entityId(entityId)
                .type(type)
                .slug(slug)
                .title(resolvedTitle)
                .description(description)
                .image(ImageDto.builder().url(imageUrl).alt(resolvedTitle).build())
                .locale(locale)
                .featured(featured)
                .featuredOrder(featuredOrder)
                .displayOrder(0)
                .active(true)
                .build();
    }

    private String resolveFeaturedLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return "ckb";
        }
        String normalized = locale.trim().toLowerCase(Locale.ROOT);
        return "kmr".equals(normalized) || "ku".equals(normalized) ? "kmr" : "ckb";
    }

    private String localized(String ckb, String kmr, boolean useKmr) {
        return useKmr ? firstNonBlank(kmr, ckb) : firstNonBlank(ckb, kmr);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Tiptap HTML -> one short plain-text line for a carousel slide.
     *
     * Tags are dropped, the handful of entities Tiptap emits are decoded, whitespace is
     * collapsed, and the result is cut at a word boundary. Fallback only — an admin-authored
     * featureDescription always wins.
     */
    private String plainTextExcerpt(String html) {
        if (isBlank(html)) return null;
        String text = HTML_TAG.matcher(html).replaceAll(" ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        text = WHITESPACE.matcher(text).replaceAll(" ").trim();
        if (text.isEmpty()) return null;
        if (text.length() <= FEATURED_EXCERPT_MAX_CHARS) return text;
        int cut = text.lastIndexOf(' ', FEATURED_EXCERPT_MAX_CHARS);
        return text.substring(0, cut > 0 ? cut : FEATURED_EXCERPT_MAX_CHARS).trim() + "…";
    }

    // Team and partners

    @Transactional(readOnly = true)
    public List<TeamMemberResponse> getTeam() {
        return teamRepository.findAllByActiveTrueOrderByDisplayOrderAsc().stream()
                .map(this::teamResponse).toList();
    }

    @Transactional
    public TeamMemberResponse createTeamMember(TeamMemberRequest request) {
        TeamMember member = new TeamMember();
        applyTeam(member, request);
        return teamResponse(teamRepository.save(member));
    }

    @Transactional
    public TeamMemberResponse updateTeamMember(Long id, TeamMemberRequest request) {
        TeamMember member = teamRepository.findById(id)
                .orElseThrow(() -> notFound("Team member", id));
        applyTeam(member, request);
        return teamResponse(teamRepository.save(member));
    }

    @Transactional
    public void deleteTeamMember(Long id) {
        if (!teamRepository.existsById(id)) throw notFound("Team member", id);
        teamRepository.deleteById(id);
    }

    private void applyTeam(TeamMember member, TeamMemberRequest request) {
        member.setNameCkb(request.getNameCkb().trim());
        member.setNameKmr(trimToNull(request.getNameKmr()));
        member.setRoleCkb(request.getRoleCkb().trim());
        member.setRoleKmr(trimToNull(request.getRoleKmr()));
        member.setBioCkb(trimToNull(request.getBioCkb()));
        member.setBioKmr(trimToNull(request.getBioKmr()));
        member.setOffice(trimToNull(request.getOffice()));
        member.setImageUrl(trimToNull(request.getImageUrl()));
        member.setDisplayOrder(defaultOrder(request.getDisplayOrder()));
        member.setActive(request.getActive() == null || request.getActive());
    }

    @Transactional(readOnly = true)
    public List<PartnerResponse> getPartners() {
        return partnerRepository.findAllByActiveTrueOrderByDisplayOrderAsc().stream()
                .map(this::partnerResponse).toList();
    }

    @Transactional
    public PartnerResponse createPartner(PartnerRequest request) {
        Partner partner = new Partner();
        applyPartner(partner, request);
        return partnerResponse(partnerRepository.save(partner));
    }

    @Transactional
    public PartnerResponse updatePartner(Long id, PartnerRequest request) {
        Partner partner = partnerRepository.findById(id)
                .orElseThrow(() -> notFound("Partner", id));
        applyPartner(partner, request);
        return partnerResponse(partnerRepository.save(partner));
    }

    @Transactional
    public void deletePartner(Long id) {
        if (!partnerRepository.existsById(id)) throw notFound("Partner", id);
        partnerRepository.deleteById(id);
    }

    private void applyPartner(Partner partner, PartnerRequest request) {
        partner.setNameCkb(request.getNameCkb().trim());
        partner.setNameKmr(trimToNull(request.getNameKmr()));
        partner.setDescriptionCkb(trimToNull(request.getDescriptionCkb()));
        partner.setDescriptionKmr(trimToNull(request.getDescriptionKmr()));
        partner.setLogoUrl(trimToNull(request.getLogoUrl()));
        partner.setWebsiteUrl(trimToNull(request.getWebsiteUrl()));
        partner.setDisplayOrder(defaultOrder(request.getDisplayOrder()));
        partner.setActive(request.getActive() == null || request.getActive());
    }

    // Contact messages

    @Transactional
    public ContactMessageResponse submitContactMessage(ContactMessageRequest request) {
        ContactMessage message = ContactMessage.builder()
                .name(request.getName().trim())
                .email(request.getEmail().trim())
                .phone(trimToNull(request.getPhone()))
                .subject(request.getSubject().trim())
                .message(request.getMessage())
                .locale(trimToNull(request.getLocale()))
                .status("NEW")
                .build();
        return contactMessageResponse(contactMessageRepository.save(message));
    }

    @Transactional(readOnly = true)
    public Page<ContactMessageResponse> getContactMessages(int page, int size) {
        return contactMessageRepository.findAll(newest(page, size))
                .map(this::contactMessageResponse);
    }

    @Transactional
    public ContactMessageResponse updateContactMessageStatus(Long id, StatusRequest request) {
        ContactMessage message = contactMessageRepository.findById(id)
                .orElseThrow(() -> notFound("Contact message", id));
        message.setStatus(validateStatus(request.getStatus()));
        return contactMessageResponse(contactMessageRepository.save(message));
    }

    // Social links

    @Transactional(readOnly = true)
    public List<SocialLinkResponse> getSocialLinks() {
        return socialLinkRepository.findAllByActiveTrueOrderByDisplayOrderAsc().stream()
                .map(this::socialResponse).toList();
    }

    @Transactional
    public SocialLinkResponse createSocialLink(SocialLinkRequest request) {
        SocialLink link = new SocialLink();
        applySocial(link, request);
        return socialResponse(socialLinkRepository.save(link));
    }

    @Transactional
    public SocialLinkResponse updateSocialLink(Long id, SocialLinkRequest request) {
        SocialLink link = socialLinkRepository.findById(id)
                .orElseThrow(() -> notFound("Social link", id));
        applySocial(link, request);
        return socialResponse(socialLinkRepository.save(link));
    }

    @Transactional
    public void deleteSocialLink(Long id) {
        if (!socialLinkRepository.existsById(id)) throw notFound("Social link", id);
        socialLinkRepository.deleteById(id);
    }

    private void applySocial(SocialLink link, SocialLinkRequest request) {
        link.setPlatform(request.getPlatform().trim().toUpperCase(Locale.ROOT));
        link.setUrl(request.getUrl().trim());
        link.setLabelCkb(trimToNull(request.getLabelCkb()));
        link.setLabelKmr(trimToNull(request.getLabelKmr()));
        link.setDisplayOrder(defaultOrder(request.getDisplayOrder()));
        link.setActive(request.getActive() == null || request.getActive());
    }

    // Donations

    @Transactional(readOnly = true)
    public DonationSettingsResponse getDonationSettings() {
        return donationSettingsRepository.findAll().stream().findFirst()
                .map(this::donationSettingsResponse)
                .orElseGet(() -> DonationSettingsResponse.builder()
                        .financialDonationsEnabled(true)
                        .archiveDonationsEnabled(true)
                        .featured(false)
                        .build());
    }

    @Transactional(readOnly = true)
    public List<DonationTypeResponse> getDonationTypes() {
        DonationSettingsResponse settings = getDonationSettings();
        return List.of(
                DonationTypeResponse.builder()
                        .code("FINANCIAL")
                        .titleCkb("بەخشینی دارایی")
                        .titleKmr("Bexşîna aborî")
                        .enabled(settings.getFinancialDonationsEnabled())
                        .build(),
                DonationTypeResponse.builder()
                        .code("ARCHIVE")
                        .titleCkb("بەخشینی ئەرشیفی")
                        .titleKmr("Bexşîna arşîvê")
                        .enabled(settings.getArchiveDonationsEnabled())
                        .build()
        );
    }

    @Transactional
    public DonationSettingsResponse saveDonationSettings(DonationSettingsRequest request) {
        DonationSettings settings = donationSettingsRepository.findAll().stream().findFirst()
                .orElseGet(DonationSettings::new);
        settings.setTitleCkb(trimToNull(request.getTitleCkb()));
        settings.setTitleKmr(trimToNull(request.getTitleKmr()));
        settings.setDescriptionCkb(trimToNull(request.getDescriptionCkb()));
        settings.setDescriptionKmr(trimToNull(request.getDescriptionKmr()));
        settings.setHeroImageUrl(trimToNull(request.getHeroImageUrl()));
        settings.setBankName(trimToNull(request.getBankName()));
        settings.setAccountName(trimToNull(request.getAccountName()));
        settings.setAccountNumber(trimToNull(request.getAccountNumber()));
        settings.setIban(trimToNull(request.getIban()));
        settings.setSwiftCode(trimToNull(request.getSwiftCode()));
        settings.setPaymentInstructionsCkb(trimToNull(request.getPaymentInstructionsCkb()));
        settings.setPaymentInstructionsKmr(trimToNull(request.getPaymentInstructionsKmr()));
        settings.setFinancialDonationsEnabled(
                request.getFinancialDonationsEnabled() == null
                        || request.getFinancialDonationsEnabled());
        settings.setArchiveDonationsEnabled(
                request.getArchiveDonationsEnabled() == null
                        || request.getArchiveDonationsEnabled());

        // Featured fields are null-tolerant on purpose: this is a full-object PUT, and a client
        // that does not send them must not silently unfeature the page. Same cap and same image
        // requirement as setDonationFeatured().
        if (request.getFeatureImageUrl() != null) {
            settings.setFeatureImageUrl(trimToNull(request.getFeatureImageUrl()));
        }
        if (request.getFeatured() != null) {
            boolean turningOn = request.getFeatured();
            if (turningOn && !settings.isFeatured()
                    && countAllFeatured() >= getMaxFeaturedSlides()) {
                throw new IllegalStateException(
                        "Maximum of " + getMaxFeaturedSlides()
                                + " featured slides allowed across all content. "
                                + "Unfeature one first.");
            }
            if (turningOn && isBlank(firstNonBlank(
                    settings.getFeatureImageUrl(), settings.getHeroImageUrl()))) {
                throw new IllegalArgumentException(
                        "featureImageUrl or heroImageUrl is required to feature the donation page.");
            }
            settings.setFeatured(turningOn);
            if (!turningOn) {
                settings.setFeaturedOrder(null);
            }
        }
        if (request.getFeaturedOrder() != null && settings.isFeatured()) {
            settings.setFeaturedOrder(request.getFeaturedOrder());
        }
        return donationSettingsResponse(donationSettingsRepository.save(settings));
    }

    @Transactional
    public FinancialDonationResponse submitFinancialDonation(FinancialDonationRequest request) {
        ensureFinancialDonationsEnabled();
        FinancialDonation donation = FinancialDonation.builder()
                .donorName(request.getDonorName().trim())
                .email(orEmpty(request.getEmail()))
                .phone(trimToNull(request.getPhone()))
                .amount(request.getAmount())
                .currency(validateCurrency(request.getCurrency()))
                .paymentMethod(request.getPaymentMethod().trim())
                .transactionReference(trimToNull(request.getTransactionReference()))
                .message(trimToNull(request.getMessage()))
                .status("PENDING")
                .build();
        return financialResponse(financialDonationRepository.save(donation));
    }

    @Transactional
    public ArchiveDonationResponse submitArchiveDonation(ArchiveDonationRequest request) {
        ensureArchiveDonationsEnabled();
        ArchiveDonation donation = ArchiveDonation.builder()
                .donorName(request.getDonorName().trim())
                .email(orEmpty(request.getEmail()))
                .phone(trimToNull(request.getPhone()))
                .materialType(validateMaterialType(request.getMaterialType()))
                .title(orEmpty(request.getTitle()))
                .description(orEmpty(request.getDescription()))
                .estimatedDate(trimToNull(request.getEstimatedDate()))
                .attachmentUrl(trimToNull(request.getAttachmentUrl()))
                .status("PENDING")
                .build();
        return archiveResponse(archiveDonationRepository.save(donation));
    }

    @Transactional(readOnly = true)
    public Page<FinancialDonationResponse> getFinancialDonations(int page, int size) {
        return financialDonationRepository.findAll(newest(page, size))
                .map(this::financialResponse);
    }

    @Transactional(readOnly = true)
    public Page<ArchiveDonationResponse> getArchiveDonations(int page, int size) {
        return archiveDonationRepository.findAll(newest(page, size))
                .map(this::archiveResponse);
    }

    @Transactional
    public FinancialDonationResponse updateFinancialStatus(Long id, StatusRequest request) {
        FinancialDonation donation = financialDonationRepository.findById(id)
                .orElseThrow(() -> notFound("Financial donation", id));
        donation.setStatus(validateStatus(request.getStatus()));
        return financialResponse(financialDonationRepository.save(donation));
    }

    @Transactional
    public ArchiveDonationResponse updateArchiveStatus(Long id, StatusRequest request) {
        ArchiveDonation donation = archiveDonationRepository.findById(id)
                .orElseThrow(() -> notFound("Archive donation", id));
        donation.setStatus(validateStatus(request.getStatus()));
        return archiveResponse(archiveDonationRepository.save(donation));
    }

    private void ensureFinancialDonationsEnabled() {
        donationSettingsRepository.findAll().stream().findFirst()
                .filter(settings -> !settings.isFinancialDonationsEnabled())
                .ifPresent(settings -> {
                    throw new IllegalStateException("Financial donations are disabled");
                });
    }

    private void ensureArchiveDonationsEnabled() {
        donationSettingsRepository.findAll().stream().findFirst()
                .filter(settings -> !settings.isArchiveDonationsEnabled())
                .ifPresent(settings -> {
                    throw new IllegalStateException("Archive donations are disabled");
                });
    }

    // Mappers

    private TeamMemberResponse teamResponse(TeamMember member) {
        return TeamMemberResponse.builder()
                .id(member.getId()).nameCkb(member.getNameCkb()).nameKmr(member.getNameKmr())
                .roleCkb(member.getRoleCkb()).roleKmr(member.getRoleKmr())
                .bioCkb(member.getBioCkb()).bioKmr(member.getBioKmr())
                .office(member.getOffice()).imageUrl(member.getImageUrl())
                .displayOrder(member.getDisplayOrder()).active(member.isActive()).build();
    }

    private PartnerResponse partnerResponse(Partner partner) {
        return PartnerResponse.builder()
                .id(partner.getId()).nameCkb(partner.getNameCkb()).nameKmr(partner.getNameKmr())
                .descriptionCkb(partner.getDescriptionCkb())
                .descriptionKmr(partner.getDescriptionKmr())
                .logoUrl(partner.getLogoUrl()).websiteUrl(partner.getWebsiteUrl())
                .displayOrder(partner.getDisplayOrder()).active(partner.isActive()).build();
    }

    private ContactMessageResponse contactMessageResponse(ContactMessage message) {
        return ContactMessageResponse.builder()
                .id(message.getId()).name(message.getName()).email(message.getEmail())
                .phone(message.getPhone()).subject(message.getSubject())
                .message(message.getMessage()).locale(message.getLocale())
                .status(message.getStatus()).createdAt(message.getCreatedAt()).build();
    }

    private SocialLinkResponse socialResponse(SocialLink link) {
        return SocialLinkResponse.builder()
                .id(link.getId()).platform(link.getPlatform()).url(link.getUrl())
                .labelCkb(link.getLabelCkb()).labelKmr(link.getLabelKmr())
                .displayOrder(link.getDisplayOrder()).active(link.isActive()).build();
    }

    private DonationSettingsResponse donationSettingsResponse(DonationSettings settings) {
        return DonationSettingsResponse.builder()
                .id(settings.getId()).titleCkb(settings.getTitleCkb())
                .titleKmr(settings.getTitleKmr())
                .descriptionCkb(settings.getDescriptionCkb())
                .descriptionKmr(settings.getDescriptionKmr())
                .heroImageUrl(settings.getHeroImageUrl()).bankName(settings.getBankName())
                .accountName(settings.getAccountName()).accountNumber(settings.getAccountNumber())
                .iban(settings.getIban()).swiftCode(settings.getSwiftCode())
                .paymentInstructionsCkb(settings.getPaymentInstructionsCkb())
                .paymentInstructionsKmr(settings.getPaymentInstructionsKmr())
                .financialDonationsEnabled(settings.isFinancialDonationsEnabled())
                .archiveDonationsEnabled(settings.isArchiveDonationsEnabled())
                .featured(settings.isFeatured())
                .featuredOrder(settings.getFeaturedOrder())
                .featureImageUrl(settings.getFeatureImageUrl()).build();
    }

    private FinancialDonationResponse financialResponse(FinancialDonation donation) {
        return FinancialDonationResponse.builder()
                .id(donation.getId()).donorName(donation.getDonorName())
                .email(donation.getEmail()).phone(donation.getPhone())
                .amount(donation.getAmount()).currency(donation.getCurrency())
                .paymentMethod(donation.getPaymentMethod())
                .transactionReference(donation.getTransactionReference())
                .message(donation.getMessage())
                .status(donation.getStatus()).createdAt(donation.getCreatedAt()).build();
    }

    private ArchiveDonationResponse archiveResponse(ArchiveDonation donation) {
        return ArchiveDonationResponse.builder()
                .id(donation.getId()).donorName(donation.getDonorName())
                .email(donation.getEmail()).phone(donation.getPhone())
                .materialType(donation.getMaterialType()).title(donation.getTitle())
                .description(donation.getDescription())
                .estimatedDate(donation.getEstimatedDate())
                .attachmentUrl(donation.getAttachmentUrl())
                .status(donation.getStatus()).createdAt(donation.getCreatedAt()).build();
    }

    private PageRequest newest(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private int defaultOrder(Integer order) {
        return order == null ? 0 : order;
    }

    private String validateStatus(String status) {
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!SUBMISSION_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported status: " + status);
        }
        return normalized;
    }

    private String validateMaterialType(String materialType) {
        String normalized = materialType.trim().toUpperCase(Locale.ROOT);
        if (!ARCHIVE_MATERIAL_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported materialType: " + materialType
                    + " (allowed: " + ARCHIVE_MATERIAL_TYPES + ")");
        }
        return normalized;
    }

    private String validateCurrency(String currency) {
        String normalized = currency.trim().toUpperCase(Locale.ROOT);
        if (!DONATION_CURRENCIES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported currency: " + currency
                    + " (allowed: " + DONATION_CURRENCIES + ")");
        }
        return normalized;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Null-safe for NOT NULL columns whose value is optional at the API level. */
    private String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private EntityNotFoundException notFound(String resource, Long id) {
        return new EntityNotFoundException(resource + " not found: " + id);
    }
}
