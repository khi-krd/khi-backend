package ak.dev.khi_backend.khi_app.service.publishment.video;

import ak.dev.khi_backend.khi_app.dto.publishment.video.VideoDTO;
import ak.dev.khi_backend.khi_app.dto.publishment.video.VideoMapper;
import ak.dev.khi_backend.khi_app.exceptions.BadRequestException;
import ak.dev.khi_backend.khi_app.exceptions.Errors;
import ak.dev.khi_backend.khi_app.model.publishment.topic.PublishmentTopic;
import ak.dev.khi_backend.khi_app.model.publishment.video.Video;
import ak.dev.khi_backend.khi_app.model.publishment.video.VideoClipItem;
import ak.dev.khi_backend.khi_app.model.publishment.video.VideoLog;
import ak.dev.khi_backend.khi_app.model.publishment.video.VideoSourceFile;
import ak.dev.khi_backend.khi_app.model.publishment.video.VideoType;
import ak.dev.khi_backend.khi_app.model.publishment.video.FilmReklamVideo;
import ak.dev.khi_backend.khi_app.repository.publishment.topic.PublishmentTopicRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.video.FilmReklamVideoRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.video.VideoLogRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.video.VideoRepository;
import ak.dev.khi_backend.khi_app.service.S3Service;
import ak.dev.khi_backend.khi_app.service.media.TiptapHtmlProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * سێرڤیسی ڤیدیۆ - بەڕێوەبردنی فیلم و کلیپە ڤیدیۆییەکان
 *
 * ─── ناردنی فایلی ڤیدیۆ ────────────────────────────────────────────────────────
 *
 *  FILM type:
 *    videoFiles[0]  → sourceUrl دەنێت بۆ ڤیدیۆکە (فایل لەسەر URL پێشەکییە)
 *
 *  VIDEO_CLIP type:
 *    videoFiles[i]  → url دەنێت بۆ videoClipItems[i] بەپێی ئینگزەس
 *    هەر فایلێک کە بنێردرێت externalUrl و embedUrl ی کلیپەکە بەتاڵ دەکاتەوە
 *    ئەگەر فایل نەبێت بۆ ئینگزەسێک، پێویستە url/externalUrl/embedUrl لە JSON بێت
 *
 * ─── لیستی هەڵەکان ─────────────────────────────────────────────────────────────
 *
 *  ١.  video.dto.required         DTO بەتاڵە (Bad Request)
 *  ٢.  video.type.required        جۆری ڤیدیۆ نەدراوە (Bad Request)
 *  ٣.  video.id.required          ئایدی پێویستە (Bad Request)
 *  ٤.  video.not_found            ڤیدیۆکە نەدۆزرایەوە (Not Found)
 *  ٥.  video.clip.source.required کلیپ بێ سەرچاوەیە (Bad Request)
 *  ٦.  video.clip.id.invalid      ئایدیی کلیپ لە ئەم ڤیدیۆیەدا نییە (Bad Request)
 *  ٧.  video.clip.id.duplicate    ئایدیی کلیپ دووبارە هاتووە (Bad Request)
 *  ٨.  topic.not_found            بابەت نەدۆزرایەوە (Not Found)
 *  ٩.  topic.type.mismatch        بابەت بۆ VIDEO نییە (Bad Request)
 *  ١٠. video.topic.names.required ناوی بابەت پێویستە (Bad Request)
 *  ١١. search.keyword.required    کلیلەووشەی گەڕان پێویستە (Bad Request)
 *  ١٢. search.tag.required        تاگی گەڕان پێویستە (Bad Request)
 *  ١٣. media.upload.failed        شکستی ناردنی فایل بۆ S3 (Bad Request)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoService {

    private static final String TOPIC_ENTITY_TYPE = "VIDEO";
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private static final String FILM_REKLAM_VIDEO_NOT_FOUND      = "video.reklamVideo.not_found";
    private static final String FILM_REKLAM_VIDEO_ALREADY_EXISTS = "video.reklamVideo.already_exists";

    private final VideoRepository            videoRepository;
    private final VideoLogRepository         videoLogRepository;
    private final PublishmentTopicRepository topicRepository;
    private final FilmReklamVideoRepository  filmReklamVideoRepository;
    private final S3Service                  s3Service;
    private final TiptapHtmlProcessor        tiptapHtmlProcessor;

    // ═══════════════════════════════════════════════════════════════════════════
    // بابەت - دروستکردن، خوێندنەوە، سڕینەوە
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * دروستکردنی بابەتی ڤیدیۆ
     *
     * @throws BadRequestException - "ناوی بابەت پێویستە" (ئەگەر هەردووکی بەتاڵ بن)
     */
    @Transactional
    public VideoDTO.TopicView createTopic(String nameCkb, String nameKmr) {
        if (isBlank(nameCkb) && isBlank(nameKmr)) {
            // هەڵە: دەبێت لانیکەم ناوێکی کوردی بنووسرێت بۆ بابەت
            throw new BadRequestException("video.topic.names.required",
                    Map.of("message", "بابەت پێویستی بە لانیکەم ناوێکی کوردییە (ناوەندی یان باکوور)"));
        }
        PublishmentTopic topic = PublishmentTopic.builder()
                .entityType(TOPIC_ENTITY_TYPE)
                .nameCkb(trimOrNull(nameCkb))
                .nameKmr(trimOrNull(nameKmr))
                .build();
        PublishmentTopic saved = topicRepository.save(topic);
        log.info("بابەتی VIDEO دروستکرا id={} ckb='{}' kmr='{}'", saved.getId(), saved.getNameCkb(), saved.getNameKmr());
        return toTopicView(saved);
    }

    @Transactional(readOnly = true)
    public List<VideoDTO.TopicView> getTopics() {
        return topicRepository.findByEntityType(TOPIC_ENTITY_TYPE)
                .stream()
                .map(this::toTopicView)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public VideoDTO.TopicView getTopicById(Long topicId) {
        return toTopicView(findTopicOrThrow(topicId));
    }

    /**
     * سڕینەوەی بابەت بە دابەزاندنی پەیوەندی لەگەڵ ڤیدیۆکان
     *
     * @throws NotFoundException - "بابەت نەدۆزرایەوە"
     */
    @Transactional
    public void deleteTopic(Long topicId) {
        PublishmentTopic topic = findTopicOrThrow(topicId);

        List<Video> linked = videoRepository.findByTopicId(topicId);
        for (Video v : linked) v.setTopic(null);

        if (!linked.isEmpty()) {
            videoRepository.saveAll(linked);
            log.info("بابەت id={} جیاکرایەوە لە {} ڤیدیۆ", topicId, linked.size());
        }

        topicRepository.delete(topic);
        log.info("بابەتی VIDEO سڕایەوە id={}", topicId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // دروستکردن (زیادکردن)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * دروستکردنی ڤیدیۆی نوێ
     *
     * @param dto          زانیاری ڤیدیۆ (JSON)
     * @param ckbCoverImage وێنەی بەرگی CKB (ئارەزوومەندانە)
     * @param kmrCoverImage وێنەی بەرگی KMR (ئارەزوومەندانە)
     * @param hoverImage   وێنەی هاڤەر (ئارەزوومەندانە)
     * @param videoFiles   لیستی فایلەکانی ڤیدیۆ:
     *                       FILM       → videoFiles[0] = فایلی فیلم
     *                       VIDEO_CLIP → videoFiles[i] = فایلی کلیپی i
     *
     * @throws BadRequestException video.dto.required      — DTO بەتاڵە
     * @throws BadRequestException video.type.required     — جۆری ڤیدیۆ نەدراوە
     * @throws BadRequestException video.clip.source.required — کلیپ بێ سەرچاوەیە
     * @throws BadRequestException media.upload.failed     — شکستی ناردنی فایل
     */
    @Transactional
    public VideoDTO addVideo(
            VideoDTO dto,
            MultipartFile ckbCoverImage,
            MultipartFile kmrCoverImage,
            MultipartFile hoverImage,
            List<MultipartFile> videoFiles
    ) {
        requireDto(dto);

        // Optional covers
        String ckbUrl = resolveCoverUrl(dto.getCkbCoverUrl(), ckbCoverImage);
        String kmrUrl = resolveCoverUrl(dto.getKmrCoverUrl(), kmrCoverImage);
        String hoverUrl = resolveCoverUrl(dto.getHoverCoverUrl(), hoverImage);

        Video video = VideoMapper.toEntity(dto);

        // set if present
        if (!isBlank(ckbUrl)) {
            video.setCkbCoverUrl(ckbUrl);
        }

        if (!isBlank(kmrUrl)) {
            video.setKmrCoverUrl(kmrUrl);
        }

        if (!isBlank(hoverUrl)) {
            video.setHoverCoverUrl(hoverUrl);
        }

        // Topic
        video.setTopic(resolveOrCreateTopic(dto.getTopicId(), dto.getNewTopic()));

        enforceAlbumRule(video, dto);

        if (video.getVideoType() == VideoType.FILM) {
            applyVideoSource(video, dto, videoFiles);

            if (video.getVideoClipItems() != null) {
                video.getVideoClipItems().clear();
            }
        } else {
            clearFilmSourceFields(video);
            buildAndAttachClipItems(video, dto, videoFiles);
        }

        processTiptapHtml(video);

        Video saved = videoRepository.save(video);

        logAction(
                saved.getId(),
                getTitle(saved),
                "CREATED",
                "ڤیدیۆ دروستکرا: جۆر=" + saved.getVideoType() +
                        ", ئەلبومی بیرەوەری=" + saved.isAlbumOfMemories()
        );

        return VideoMapper.toDTO(saved);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // خوێندنەوە
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Page<VideoDTO> getAllVideos(int page, int size) {
        return videoRepository.findAll(buildPageable(page, size)).map(VideoMapper::toDTO);
    }

    @Transactional(readOnly = true)
    public Page<VideoDTO> getFeatured(int page, int size) {
        return videoRepository.findFeaturedWithTopic(buildFeaturedPageable(page, size))
                .map(VideoMapper::toDTO);
    }

    @Transactional(readOnly = true)
    public Page<VideoDTO> getVideoListing(VideoType videoType, Boolean albumOfMemories,
                                          Long topicId, int page, int size) {
        Pageable pageable = buildPageable(page, size);
        if (topicId != null) {
            return videoRepository.findAllByTopicId(topicId, pageable).map(VideoMapper::toDTO);
        }
        if (videoType == VideoType.VIDEO_CLIP && albumOfMemories != null) {
            return videoRepository.findAllClipsByAlbumFlag(albumOfMemories, pageable)
                    .map(VideoMapper::toDTO);
        }
        if (videoType != null) {
            return videoRepository.findAllByType(videoType, pageable).map(VideoMapper::toDTO);
        }
        return videoRepository.findAllWithTopic(pageable).map(VideoMapper::toDTO);
    }

    /**
     * هێنانی ڤیدیۆ بەپێی ئایدی
     *
     * @throws NotFoundException - "ڤیدیۆکە نەدۆزرایەوە"
     * @throws BadRequestException - "ئایدیی ڤیدیۆ پێویستە"
     */
    @Transactional(readOnly = true)
    public VideoDTO getVideoById(Long id) {
        return VideoMapper.toDTO(findOrThrow(id));
    }

    /**
     * گەڕانی ڤیدیۆ بەپێی کلیلەووشە
     *
     * @throws BadRequestException - "کلیلەووشەی گەڕان پێویستە"
     */
    @Transactional(readOnly = true)
    public Page<VideoDTO> searchByKeyword(String keyword, int page, int size) {
        String normalized = normalizeRequiredSearch(keyword, "keyword");
        return videoRepository.searchByKeyword(normalized, buildPageable(page, size)).map(VideoMapper::toDTO);
    }

    /**
     * گەڕانی ڤیدیۆ بەپێی تاگ
     *
     * @throws BadRequestException - "تاگی گەڕان پێویستە"
     */
    @Transactional(readOnly = true)
    public Page<VideoDTO> searchByTag(String tag, int page, int size) {
        String normalized = normalizeRequiredSearch(tag, "tag");
        return videoRepository.searchByTag(normalized, buildPageable(page, size)).map(VideoMapper::toDTO);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // نوێکردنەوە
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * نوێکردنەوەی زانیاری ڤیدیۆ
     *
     * نووسینەوەی بەشی: فیێلدی null لە DTO بەرپرسایەتی نییە (نابێت بگۆڕدرێت).
     *
     * @param id           ئایدیی ڤیدیۆی ئاماژەکراو
     * @param dto          زانیاری نوێ (JSON)
     * @param ckbCoverImage وێنەی بەرگی CKB — ئەگەر بنێردرێت جێگای کۆنەکەی دەگرێت
     * @param kmrCoverImage وێنەی بەرگی KMR — ئەگەر بنێردرێت جێگای کۆنەکەی دەگرێت
     * @param hoverImage   وێنەی هاڤەر — ئەگەر بنێردرێت جێگای کۆنەکەی دەگرێت
     * @param videoFiles   لیستی فایلەکانی ڤیدیۆ بۆ جێگرتن:
     *                       FILM       → videoFiles[0] جێگای sourceUrl دەگرێت
     *                       VIDEO_CLIP → videoFiles[i] جێگای url ی کلیپی i دەگرێت;
     *                                   ئەگەر null بێت، سەرچاوەی کۆنی کلیپەکە دەمێنێتەوە
     *
     * @throws BadRequestException video.dto.required         — DTO بەتاڵە
     * @throws BadRequestException video.id.required          — ئایدی نەدراوە
     * @throws NotFoundException   video.not_found            — ڤیدیۆکە نەدۆزرایەوە
     * @throws BadRequestException video.clip.source.required — کلیپ بێ سەرچاوەیە
     * @throws BadRequestException video.clip.id.invalid      — ئایدیی کلیپ هەڵەیە
     * @throws BadRequestException video.clip.id.duplicate    — ئایدیی کلیپ دووبارە
     * @throws BadRequestException media.upload.failed        — شکستی ناردنی فایل
     */
    @Transactional
    public VideoDTO updateVideo(
            Long id,
            VideoDTO dto,
            MultipartFile ckbCoverImage,
            MultipartFile kmrCoverImage,
            MultipartFile hoverImage,
            List<MultipartFile> videoFiles
    ) {
        requireDto(dto);

        Video video = findOrThrow(id);
        VideoType targetType = dto.getVideoType() != null
                ? dto.getVideoType()
                : video.getVideoType();

        // Validate nested clip identities and sources before any cover/video or
        // Tiptap upload. Existing clips may retain their persisted source.
        if (targetType == VideoType.VIDEO_CLIP && dto.getVideoClipItems() != null) {
            validateClipUpdate(video, dto.getVideoClipItems());
        }

        boolean updatesTopic = !dto.isClearTopic()
                && (dto.getTopicId() != null || dto.getNewTopic() != null);
        PublishmentTopic resolvedTopic = updatesTopic
                ? resolveOrCreateTopic(dto.getTopicId(), dto.getNewTopic())
                : null;

        VideoMapper.updateEntity(video, dto);

        // ✅ نوێکردنەوەی ٣ وێنەی بەرگ بە سەربەخۆیی (تەنها ئەگەن نێردرابن)
        applyCoverUpdate(video, dto, ckbCoverImage, kmrCoverImage, hoverImage);

        // بابەت: clearTopic > topicId > newTopic
        if (dto.isClearTopic()) {
            video.setTopic(null);
        } else if (updatesTopic) {
            video.setTopic(resolvedTopic);
        }

        enforceAlbumRule(video, dto);

        if (video.getVideoType() == VideoType.FILM) {
            clearClipItems(video);
            applyVideoSourceForUpdate(video, dto, videoFiles);
        } else {
            clearFilmSourceFields(video);
            if (dto.getVideoClipItems() != null) {
                List<VideoClipItem> mergedClips = mergeClipItems(
                        video, dto.getVideoClipItems(), videoFiles);
                clearClipItems(video);
                video.getVideoClipItems().addAll(mergedClips);
            }
        }

        processTiptapHtml(video);

        Video updated = videoRepository.save(video);
        logAction(updated.getId(), getTitle(updated), "UPDATED", "ڤیدیۆ نوێکرایەوە");
        return VideoMapper.toDTO(updated);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // سڕینەوە
    // ═══════════════════════════════════════════════════════════════════════════

    /** Idempotent delete: missing/mock/already-deleted IDs are successful no-ops. */
    @Transactional
    public void deleteVideo(Long id) {
        if (id == null) return;

        Video video = videoRepository.findById(id).orElse(null);
        if (video == null) {
            log.debug("Video delete ignored; id={} does not exist", id);
            return;
        }
        String title = getTitle(video);
        videoRepository.delete(video);
        logAction(id, title, "DELETED", "ڤیدیۆ بە تەواوی سڕایەوە");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // یاریدەدەرەکانی بابەت
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * ڕیزبەندی چارەسەرکردن:
     *  ١. topicId هەبێت → دۆزینەوەی بابەتی بەرەمە
     *  ٢. newTopic هەبێت → دروستکردنی بابەتی نوێ
     *  ٣. هیچیان نەبێت → null
     *
     * @throws NotFoundException   - "بابەت نەدۆزرایەوە"
     * @throws BadRequestException - "جۆری بابەت هەڵەیە"
     * @throws BadRequestException - "ناوی بابەت پێویستە"
     */
    private PublishmentTopic resolveOrCreateTopic(Long topicId, VideoDTO.InlineTopicRequest newTopic) {
        if (topicId != null) return findTopicOrThrow(topicId);

        if (newTopic != null) {
            if (isBlank(newTopic.getNameCkb()) && isBlank(newTopic.getNameKmr())) {
                // هەڵە: دەبێت لانیکەم ناوێکی کوردی بنووسرێت
                throw new BadRequestException("video.topic.names.required",
                        Map.of("message", "بابەتی نوێ پێویستی بە لانیکەم ناوێکی کوردییە"));
            }
            PublishmentTopic created = topicRepository.save(
                    PublishmentTopic.builder()
                            .entityType(TOPIC_ENTITY_TYPE)
                            .nameCkb(trimOrNull(newTopic.getNameCkb()))
                            .nameKmr(trimOrNull(newTopic.getNameKmr()))
                            .build()
            );
            log.info("بابەتی VIDEO دروستکرا inline id={}", created.getId());
            return created;
        }
        return null;
    }

    private PublishmentTopic findTopicOrThrow(Long topicId) {
        PublishmentTopic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> Errors.notFound("topic.not_found", Map.of("id", topicId)));
        if (!Objects.equals(TOPIC_ENTITY_TYPE, topic.getEntityType())) {
            // هەڵە: بابەتەکە بۆ VIDEO نییە
            throw new BadRequestException("topic.type.mismatch",
                    Map.of("message", "بابەت id=" + topicId + " بۆ '" + topic.getEntityType() +
                            "'ە، چاوەڕوان دەکرێت 'VIDEO' بێت"));
        }
        return topic;
    }

    private VideoDTO.TopicView toTopicView(PublishmentTopic t) {
        return VideoDTO.TopicView.builder()
                .id(t.getId())
                .nameCkb(t.getNameCkb())
                .nameKmr(t.getNameKmr())
                .createdAt(t.getCreatedAt())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // یاریدەدەرەکانی جۆر و ڕێساکان
    // ═══════════════════════════════════════════════════════════════════════════

    private void enforceAlbumRule(Video video, VideoDTO dto) {
        if (video.getVideoType() == VideoType.FILM) {
            video.setAlbumOfMemories(false);
            return;
        }
        if (dto.getAlbumOfMemories() != null) {
            video.setAlbumOfMemories(Boolean.TRUE.equals(dto.getAlbumOfMemories()));
        }
    }

    private void clearFilmSourceFields(Video video) {
        video.setSourceUrl(null);
        video.setSourceExternalUrl(null);
        video.setSourceEmbedUrl(null);
        if (video.getVideoSources() != null) video.getVideoSources().clear();
    }

    private void clearClipItems(Video video) {
        if (video.getVideoClipItems() != null) video.getVideoClipItems().clear();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // یاریدەدەرەکانی کلیپ
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * دروستکردن و لکاندنی مادەکانی کلیپ (بۆ CREATE)
     *
     * بۆ هەر کلیپێک (ئینگزەسی i):
     *   ١. ئەگەر videoFiles[i] هەبێت → فایل بۆ S3 دەنێردرێت، url دەبێتە سەرچاوە
     *   ٢. ئەگەر نەبێت → دەبێت url/externalUrl/embedUrl لە JSON بێت
     *
     * @throws BadRequestException video.clip.source.required — کلیپ بێ سەرچاوەیە
     */
    private void buildAndAttachClipItems(Video video, VideoDTO dto, List<MultipartFile> videoFiles) {
        if (dto == null || dto.getVideoClipItems() == null || dto.getVideoClipItems().isEmpty()) return;

        List<VideoDTO.VideoClipItemDTO> clipDtos = dto.getVideoClipItems();
        for (int i = 0; i < clipDtos.size(); i++) {
            VideoDTO.VideoClipItemDTO clipDto = clipDtos.get(i);
            if (clipDto == null) continue;

            // If a file was uploaded for this index, use it; otherwise fall back to URL fields.
            MultipartFile file = (videoFiles != null && i < videoFiles.size()) ? videoFiles.get(i) : null;
            String resolvedUrl = (file != null && !file.isEmpty()) ? uploadToS3(file) : null;

            boolean hasUploadedFile = resolvedUrl != null;
            if (!hasUploadedFile
                    && isBlank(clipDto.getUrl())
                    && isBlank(clipDto.getExternalUrl())
                    && isBlank(clipDto.getEmbedUrl())) {
                throw new BadRequestException("video.clip.source.required",
                        Map.of("field", "videoClipItems[" + i + "]",
                               "message", "هەر کلیپێک پێویستی بە لینکی ڕاستەقینە یان دەرەکی یان ئێمبێد هەیە"));
            }

            VideoClipItem item = VideoClipItem.builder()
                    .url(hasUploadedFile ? resolvedUrl : trimOrNull(clipDto.getUrl()))
                    .externalUrl(hasUploadedFile ? null : trimOrNull(clipDto.getExternalUrl()))
                    .embedUrl(hasUploadedFile ? null : trimOrNull(clipDto.getEmbedUrl()))
                    .clipNumber(clipDto.getClipNumber())
                    .durationSeconds(clipDto.getDurationSeconds())
                    .resolution(trimOrNull(clipDto.getResolution()))
                    .fileFormat(trimOrNull(clipDto.getFileFormat()))
                    .fileSizeMb(clipDto.getFileSizeMb())
                    .titleCkb(trimOrNull(clipDto.getTitleCkb()))
                    .titleKmr(trimOrNull(clipDto.getTitleKmr()))
                    .descriptionCkb(trimOrNull(clipDto.getDescriptionCkb()))
                    .descriptionKmr(trimOrNull(clipDto.getDescriptionKmr()))
                    .build();

            video.addClipItem(item);
        }
    }

    private void validateClipUpdate(
            Video video,
            List<VideoDTO.VideoClipItemDTO> clipDtos
    ) {
        Map<Long, VideoClipItem> existingById = video.getVideoClipItems().stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getId() != null)
                .collect(Collectors.toMap(
                        VideoClipItem::getId,
                        item -> item,
                        (first, ignored) -> first,
                        java.util.LinkedHashMap::new
                ));
        java.util.Set<Long> requestedIds = new java.util.HashSet<>();

        for (int i = 0; i < clipDtos.size(); i++) {
            VideoDTO.VideoClipItemDTO dto = clipDtos.get(i);
            if (dto == null) continue;

            VideoClipItem existing = resolveExistingClip(
                    existingById, requestedIds, dto, i);
            if (!hasClipSource(dto)
                    && (existing == null || !hasClipSource(existing))) {
                throw clipSourceRequired(i);
            }
        }
    }

    /**
     * نوێکردنەوەی مادەکانی کلیپ بە بەکارهێنانی ئینگزەس (بۆ UPDATE)
     *
     * بۆ هەر کلیپێک (ئینگزەسی i):
     *   ١. ئەگەر videoFiles[i] هەبێت → فایل بۆ S3 دەنێردرێت، url دەگۆڕدرێت
     *   ٢. ئەگەر نەبێت + کلیپ لە JSON سەرچاوەی هەبێت → url/externalUrl/embedUrl دەگۆڕدرێت
     *   ٣. ئەگەر هیچیان نەبێت → سەرچاوەی کۆنی کلیپەکە دەمێنێتەوە
     *
     * @throws BadRequestException video.clip.id.invalid   — ئایدیی کلیپ هەڵەیە
     * @throws BadRequestException video.clip.id.duplicate — ئایدیی کلیپ دووبارە
     */
    private List<VideoClipItem> mergeClipItems(
            Video video,
            List<VideoDTO.VideoClipItemDTO> clipDtos,
            List<MultipartFile> videoFiles
    ) {
        Map<Long, VideoClipItem> existingById = video.getVideoClipItems().stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getId() != null)
                .collect(Collectors.toMap(
                        VideoClipItem::getId,
                        item -> item,
                        (first, ignored) -> first,
                        java.util.LinkedHashMap::new
                ));
        java.util.Set<Long> requestedIds = new java.util.HashSet<>();
        List<VideoClipItem> merged = new java.util.ArrayList<>(clipDtos.size());

        for (int i = 0; i < clipDtos.size(); i++) {
            VideoDTO.VideoClipItemDTO dto = clipDtos.get(i);
            if (dto == null) continue;

            VideoClipItem item = resolveExistingClip(
                    existingById, requestedIds, dto, i);
            if (item == null) {
                item = new VideoClipItem();
                item.setVideo(video);
            }

            // If a video file was uploaded for this index, it takes priority over URL fields.
            MultipartFile file = (videoFiles != null && i < videoFiles.size()) ? videoFiles.get(i) : null;
            if (file != null && !file.isEmpty()) {
                item.setUrl(uploadToS3(file));
                item.setExternalUrl(null);
                item.setEmbedUrl(null);
            } else if (hasClipSource(dto)) {
                item.setUrl(trimOrNull(dto.getUrl()));
                item.setExternalUrl(trimOrNull(dto.getExternalUrl()));
                item.setEmbedUrl(trimOrNull(dto.getEmbedUrl()));
            }

            if (dto.getClipNumber() != null) item.setClipNumber(dto.getClipNumber());
            if (dto.getDurationSeconds() != null) item.setDurationSeconds(dto.getDurationSeconds());
            if (dto.getResolution() != null) item.setResolution(trimOrNull(dto.getResolution()));
            if (dto.getFileFormat() != null) item.setFileFormat(trimOrNull(dto.getFileFormat()));
            if (dto.getFileSizeMb() != null) item.setFileSizeMb(dto.getFileSizeMb());
            if (dto.getTitleCkb() != null) item.setTitleCkb(trimOrNull(dto.getTitleCkb()));
            if (dto.getTitleKmr() != null) item.setTitleKmr(trimOrNull(dto.getTitleKmr()));
            if (dto.getDescriptionCkb() != null) {
                item.setDescriptionCkb(trimOrNull(dto.getDescriptionCkb()));
            }
            if (dto.getDescriptionKmr() != null) {
                item.setDescriptionKmr(trimOrNull(dto.getDescriptionKmr()));
            }
            merged.add(item);
        }

        return merged;
    }

    private VideoClipItem resolveExistingClip(
            Map<Long, VideoClipItem> existingById,
            java.util.Set<Long> requestedIds,
            VideoDTO.VideoClipItemDTO dto,
            int index
    ) {
        if (dto.getId() == null) return null;

        VideoClipItem existing = existingById.get(dto.getId());
        if (existing == null) {
            throw new BadRequestException("video.clip.id.invalid", Map.of(
                    "field", "videoClipItems[" + index + "].id",
                    "id", dto.getId()));
        }
        if (!requestedIds.add(dto.getId())) {
            throw new BadRequestException("video.clip.id.duplicate", Map.of(
                    "field", "videoClipItems[" + index + "].id",
                    "id", dto.getId()));
        }
        return existing;
    }

    private boolean hasClipSource(VideoDTO.VideoClipItemDTO dto) {
        return dto != null && (!isBlank(dto.getUrl())
                || !isBlank(dto.getExternalUrl())
                || !isBlank(dto.getEmbedUrl()));
    }

    private boolean hasClipSource(VideoClipItem item) {
        return item != null && (!isBlank(item.getUrl())
                || !isBlank(item.getExternalUrl())
                || !isBlank(item.getEmbedUrl()));
    }

    private BadRequestException clipSourceRequired(int index) {
        return new BadRequestException("video.clip.source.required", Map.of(
                "field", "videoClipItems[" + index + "]",
                "message",
                "هەر کلیپێک پێویستی بە لینکی ڕاستەقینە یان دەرەکی یان ئێمبێد هەیە"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // یاریدەدەرەکانی سەرچاوە و وێنەی بەرگ
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * FILM sources on CREATE. Every uploaded file is kept (not just the first).
     * The list order is display order; exactly one source is flagged {@code main}
     * (the first added by default, or whichever the DTO marks). The main source
     * is mirrored onto the legacy sourceUrl/External/Embed columns.
     */
    private void applyVideoSource(Video video, VideoDTO dto, List<MultipartFile> videoFiles) {
        List<VideoSourceFile> sources = buildFilmSources(dto, videoFiles);
        replaceVideoSources(video, sources);
    }

    /**
     * FILM sources on UPDATE. Partial-update friendly: sources are only rebuilt
     * when the request signals intent — a real file was uploaded, {@code videoSources}
     * was supplied, or a legacy source field was provided. Otherwise the existing
     * sources (and the mirror) are left untouched.
     */
    private void applyVideoSourceForUpdate(Video video, VideoDTO dto, List<MultipartFile> videoFiles) {
        boolean hasUploadedFile = videoFiles != null
                && videoFiles.stream().anyMatch(f -> f != null && !f.isEmpty());
        boolean suppliesSources = dto != null && dto.getVideoSources() != null;
        boolean touchesLegacy = dto != null && (dto.getSourceUrl() != null
                || dto.getSourceExternalUrl() != null
                || dto.getSourceEmbedUrl() != null);

        if (!hasUploadedFile && !suppliesSources && !touchesLegacy) return;

        List<VideoSourceFile> sources = buildFilmSources(dto, videoFiles);
        replaceVideoSources(video, sources);
    }

    private void replaceVideoSources(Video video, List<VideoSourceFile> sources) {
        if (video.getVideoSources() == null) {
            video.setVideoSources(new ArrayList<>());
        }
        video.getVideoSources().clear();
        video.getVideoSources().addAll(sources);
        syncMainSourceMirror(video);
    }

    /**
     * Assemble the ordered FILM source list from the JSON DTO and uploaded files.
     *
     *  1. Seed from {@code dto.videoSources} (URL/external/embed based), or from the
     *     legacy single {@code sourceUrl/External/Embed} when that's all that's given.
     *  2. Uploaded {@code videoFiles[i]} take priority: they overwrite source i's url
     *     (clearing its external/embed), or are appended when there are more files.
     *  3. Blank sources (no url + no external + no embed) are dropped.
     *  4. Exactly one source is flagged main — the DTO's chosen one, else the first.
     */
    private List<VideoSourceFile> buildFilmSources(VideoDTO dto, List<MultipartFile> videoFiles) {
        List<VideoSourceFile> sources = new ArrayList<>();

        if (dto != null && dto.getVideoSources() != null && !dto.getVideoSources().isEmpty()) {
            for (VideoDTO.VideoSourceDTO s : dto.getVideoSources()) {
                if (s == null) continue;
                sources.add(VideoSourceFile.builder()
                        .url(trimOrNull(s.getUrl()))
                        .externalUrl(trimOrNull(s.getExternalUrl()))
                        .embedUrl(trimOrNull(s.getEmbedUrl()))
                        .label(trimOrNull(s.getLabel()))
                        .durationSeconds(s.getDurationSeconds())
                        .main(Boolean.TRUE.equals(s.getMain()))
                        .build());
            }
        } else if (dto != null && (!isBlank(dto.getSourceUrl())
                || !isBlank(dto.getSourceExternalUrl())
                || !isBlank(dto.getSourceEmbedUrl()))) {
            sources.add(VideoSourceFile.builder()
                    .url(trimOrNull(dto.getSourceUrl()))
                    .externalUrl(trimOrNull(dto.getSourceExternalUrl()))
                    .embedUrl(trimOrNull(dto.getSourceEmbedUrl()))
                    .main(true)
                    .build());
        }

        // Uploaded files win by index; extra files are appended as new sources.
        if (videoFiles != null) {
            for (int i = 0; i < videoFiles.size(); i++) {
                MultipartFile file = videoFiles.get(i);
                if (file == null || file.isEmpty()) continue;
                String url = uploadToS3(file);
                if (i < sources.size()) {
                    VideoSourceFile s = sources.get(i);
                    s.setUrl(url);
                    s.setExternalUrl(null);
                    s.setEmbedUrl(null);
                } else {
                    sources.add(VideoSourceFile.builder().url(url).main(false).build());
                }
            }
        }

        sources.removeIf(s -> isBlank(s.getUrl())
                && isBlank(s.getExternalUrl())
                && isBlank(s.getEmbedUrl()));

        normalizeMainFlag(sources);
        return sources;
    }

    /** Ensure exactly one source is main: the first flagged one, else index 0. */
    private void normalizeMainFlag(List<VideoSourceFile> sources) {
        if (sources.isEmpty()) return;
        int mainIdx = -1;
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).isMain()) { mainIdx = i; break; }
        }
        if (mainIdx < 0) mainIdx = 0;   // "the first video added" is main by default
        for (int i = 0; i < sources.size(); i++) {
            sources.get(i).setMain(i == mainIdx);
        }
    }

    /** Mirror the main source onto the legacy sourceUrl/External/Embed columns. */
    private void syncMainSourceMirror(Video video) {
        VideoSourceFile main = video.getMainSource();
        if (main == null) {
            video.setSourceUrl(null);
            video.setSourceExternalUrl(null);
            video.setSourceEmbedUrl(null);
        } else {
            video.setSourceUrl(main.getUrl());
            video.setSourceExternalUrl(main.getExternalUrl());
            video.setSourceEmbedUrl(main.getEmbedUrl());
        }
    }

    /**
     * دانانی وێنەی بەرگ: فایل لەسەر لینک پێشەکییە
     */
    private String resolveCoverUrl(String urlFromDto, MultipartFile coverFile) {
        if (coverFile != null && !coverFile.isEmpty()) return uploadToS3(coverFile);
        if (!isBlank(urlFromDto)) return urlFromDto.trim();
        return null;
    }

    /**
     * نوێکردنەوەی ٣ وێنەی بەرگ بە سەربەخۆیی
     */
    private void applyCoverUpdate(Video video, VideoDTO dto,
                                  MultipartFile ckbCoverImage,
                                  MultipartFile kmrCoverImage,
                                  MultipartFile hoverImage) {

        String ckb = resolveCoverUrl(dto.getCkbCoverUrl(), ckbCoverImage);
        if (!isBlank(ckb)) video.setCkbCoverUrl(ckb);

        String kmr = resolveCoverUrl(dto.getKmrCoverUrl(), kmrCoverImage);
        if (!isBlank(kmr)) video.setKmrCoverUrl(kmr);

        String hov = resolveCoverUrl(dto.getHoverCoverUrl(), hoverImage);
        if (!isBlank(hov)) video.setHoverCoverUrl(hov);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // یاریدەدەرەکانی ڕیپۆزیتۆری و تۆمارکردن
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * دۆزینەوەی ڤیدیۆ یان هەڵەدان
     *
     * @throws BadRequestException - "ئایدیی ڤیدیۆ پێویستە"
     * @throws NotFoundException   - "ڤیدیۆکە نەدۆزرایەوە"
     */
    private Video findOrThrow(Long id) {
        if (id == null) {
            // هەڵە: ئایدی پێویستە
            throw new BadRequestException("video.id.required", Map.of("field", "id"));
        }
        return videoRepository.findById(id)
                .orElseThrow(() -> Errors.videoNotFound(id));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FILM REKLAM VIDEO — the homepage Film section background
    // ═══════════════════════════════════════════════════════════════════════════
    //
    // A singleton: at most one row site-wide, so no id, no pagination, no locale.
    // The first upload is create(); every upload after that is update(). Mirrors
    // SoundTrackService's sound reklam video, which does the same job for the
    // Sound section — keep the two in step.

    /**
     * دروستکردنی ڤیدیۆی ڕیکلامی فیلم — تەنها یەک جار
     *
     * @throws ak.dev.khi_backend.khi_app.exceptions.publishment.video.VideoMediaException
     *         400 — فایل نەنێردراوە، یان ڤیدیۆ نییە، یان پێشتر هەیە
     */
    @Transactional
    public VideoDTO.FilmReklamVideoResponse createFilmReklamVideo(MultipartFile videoFile) {
        validatePromoVideoFile(videoFile);

        if (filmReklamVideoRepository.findTopByOrderByIdAsc().isPresent()) {
            throw Errors.videoMediaInvalid(FILM_REKLAM_VIDEO_ALREADY_EXISTS, Map.of());
        }

        FilmReklamVideo created = filmReklamVideoRepository.save(
                FilmReklamVideo.builder()
                        .videoUrl(uploadToS3(videoFile))
                        .sizeBytes(videoFile.getSize())
                        .mimeType(trimOrNull(videoFile.getContentType()))
                        .build()
        );

        return toFilmReklamVideoResponse(created);
    }

    /**
     * خوێندنەوەی ڤیدیۆی ڕیکلامی فیلم
     *
     * @throws ak.dev.khi_backend.khi_app.exceptions.AppException
     *         404 — هێشتا هیچ ڤیدیۆیەک نەنێردراوە (دۆخی ئاسایی، نەک هەڵە)
     */
    @Transactional(readOnly = true)
    public VideoDTO.FilmReklamVideoResponse getFilmReklamVideo() {
        return toFilmReklamVideoResponse(findFilmReklamVideoOrThrow());
    }

    /**
     * گۆڕینی فایلی ڤیدیۆی ڕیکلامی فیلم
     *
     * <p>Full replacement — there is no other field to patch. The new file is stored
     * first and the old S3 object is removed only after the row is saved, so a failed
     * upload leaves the existing video untouched.</p>
     */
    @Transactional
    public VideoDTO.FilmReklamVideoResponse updateFilmReklamVideo(MultipartFile videoFile) {
        validatePromoVideoFile(videoFile);

        FilmReklamVideo reklamVideo = findFilmReklamVideoOrThrow();
        String previousVideoUrl = reklamVideo.getVideoUrl();

        reklamVideo.setVideoUrl(uploadToS3(videoFile));
        reklamVideo.setSizeBytes(videoFile.getSize());
        reklamVideo.setMimeType(trimOrNull(videoFile.getContentType()));

        FilmReklamVideo saved = filmReklamVideoRepository.save(reklamVideo);
        if (!isBlank(previousVideoUrl)) {
            s3Service.deleteFile(previousVideoUrl);
        }

        return toFilmReklamVideoResponse(saved);
    }

    /**
     * سڕینەوەی ڤیدیۆی ڕیکلامی فیلم — بەشی فیلم دەگەڕێتەوە بۆ پاشبنەمای ڕەش
     */
    @Transactional
    public void deleteFilmReklamVideo() {
        FilmReklamVideo reklamVideo = findFilmReklamVideoOrThrow();
        String videoUrl = reklamVideo.getVideoUrl();

        filmReklamVideoRepository.delete(reklamVideo);
        if (!isBlank(videoUrl)) {
            s3Service.deleteFile(videoUrl);
        }
    }

    private FilmReklamVideo findFilmReklamVideoOrThrow() {
        return filmReklamVideoRepository.findTopByOrderByIdAsc()
                .orElseThrow(() -> Errors.notFound(FILM_REKLAM_VIDEO_NOT_FOUND, Map.of()));
    }

    private VideoDTO.FilmReklamVideoResponse toFilmReklamVideoResponse(FilmReklamVideo reklamVideo) {
        return VideoDTO.FilmReklamVideoResponse.builder()
                .id(reklamVideo.getId())
                .videoUrl(reklamVideo.getVideoUrl())
                .sizeBytes(reklamVideo.getSizeBytes())
                .mimeType(reklamVideo.getMimeType())
                .createdAt(reklamVideo.getCreatedAt())
                .updatedAt(reklamVideo.getUpdatedAt())
                .build();
    }

    /**
     * پشکنینی فایلی ڤیدیۆی ڕیکلام — بەکارهاتوو لە دروستکردن و گۆڕیندا
     *
     * <p>No size or duration cap beyond the Spring multipart limit; enforce any
     * ceiling in the dashboard before uploading.</p>
     */
    private void validatePromoVideoFile(MultipartFile videoFile) {
        if (videoFile == null || videoFile.isEmpty()) {
            throw Errors.videoMediaInvalid("error.validation", Map.of(
                    "field", "videoFile",
                    "message", "ڤیدیۆ پێویستە"
            ));
        }
        String contentType = trimOrNull(videoFile.getContentType());
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("video/")) {
            throw Errors.videoMediaInvalid("error.validation", Map.of(
                    "field", "videoFile",
                    "message", "فایلی نێردراو دەبێت ڤیدیۆ بێت"
            ));
        }
    }

    /**
     * ناردنی فایل بۆ S3
     *
     * @throws BadRequestException - "شکستی ناردنی فایل"
     */
    private String uploadToS3(MultipartFile file) {
        try {
            return s3Service.upload(file.getBytes(), file.getOriginalFilename(), file.getContentType());
        } catch (IOException e) {
            // هەڵە: کێشە لە ناردنی فایل
            throw new BadRequestException("media.upload.failed",
                    Map.of("filename", file.getOriginalFilename(), "error", e.getMessage()));
        }
    }

    private void logAction(Long videoId, String videoTitle, String action, String details) {
        videoLogRepository.save(VideoLog.builder()
                .videoId(videoId)
                .videoTitle(videoTitle)
                .action(action)
                .details(details)
                .timestamp(LocalDateTime.now())
                .build());
    }

    private Pageable buildPageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), DEFAULT_SORT);
    }

    private Pageable buildFeaturedPageable(int page, int size) {
        return PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.ASC, "featuredOrder")
                        .and(Sort.by(Sort.Direction.DESC, "id"))
        );
    }

    /**
     * پشتڕاستکردنەوەی گەڕان
     *
     * @throws BadRequestException - ئەگەر بەتاڵ بێت (بەپێی جۆر: "کلیلەووشەی گەڕان پێویستە" یان "تاگی گەڕان پێویستە")
     */
    private String normalizeRequiredSearch(String value, String fieldName) {
        String normalized = trimOrNull(value);
        if (isBlank(normalized)) {
            String errorCode = "keyword".equals(fieldName) ? "search.keyword.required" : "search.tag.required";
            throw new BadRequestException(errorCode, Map.of("field", fieldName));
        }
        return normalized;
    }

    /**
     * پشتڕاستکردنەوەی DTO
     *
     * @throws BadRequestException - "زانیاری ڤیدیۆ پێویستە"
     * @throws BadRequestException - "جۆری ڤیدیۆ پێویستە"
     */
    private void requireDto(VideoDTO dto) {
        if (dto == null) {
            // هەڵە: DTO بەتاڵە
            throw new BadRequestException("video.dto.required", Map.of("field", "dto"));
        }
        if (dto.getVideoType() == null) {
            // هەڵە: جۆری ڤیدیۆ پێویستە
            throw new BadRequestException("video.type.required", Map.of("field", "videoType"));
        }
    }

    private String getTitle(Video video) {
        if (video.getCkbContent() != null && !isBlank(video.getCkbContent().getTitle()))
            return video.getCkbContent().getTitle();
        if (video.getKmrContent() != null && !isBlank(video.getKmrContent().getTitle()))
            return video.getKmrContent().getTitle();
        return "ڤیدیۆی بێ ناو";
    }

    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    private String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Scan the bilingual Tiptap descriptions on the Video and all its clip items
     * and upload any inline base64 data URIs to S3, rewriting the HTML in place.
     */
    private void processTiptapHtml(Video video) {
        if (video == null) return;
        if (video.getCkbContent() != null) {
            video.getCkbContent().setDescription(
                    tiptapHtmlProcessor.process(video.getCkbContent().getDescription()));
        }
        if (video.getKmrContent() != null) {
            video.getKmrContent().setDescription(
                    tiptapHtmlProcessor.process(video.getKmrContent().getDescription()));
        }
        if (video.getVideoClipItems() != null) {
            for (VideoClipItem item : video.getVideoClipItems()) {
                if (item == null) continue;
                item.setDescriptionCkb(tiptapHtmlProcessor.process(item.getDescriptionCkb()));
                item.setDescriptionKmr(tiptapHtmlProcessor.process(item.getDescriptionKmr()));
            }
        }
    }
}
