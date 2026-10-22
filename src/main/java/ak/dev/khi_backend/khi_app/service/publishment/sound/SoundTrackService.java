package ak.dev.khi_backend.khi_app.service.publishment.sound;

import ak.dev.khi_backend.khi_app.dto.publishment.sound.SoundTrackDtos.*;
import ak.dev.khi_backend.khi_app.enums.Language;
import ak.dev.khi_backend.khi_app.enums.publishment.AttachmentType;
import ak.dev.khi_backend.khi_app.enums.publishment.TrackState;
import ak.dev.khi_backend.khi_app.enums.publishment.FileType;
import ak.dev.khi_backend.khi_app.exceptions.Errors;
import ak.dev.khi_backend.khi_app.model.publishment.sound.*;
import ak.dev.khi_backend.khi_app.repository.publishment.sound.SoundReklamVideoRepository;
import ak.dev.khi_backend.khi_app.model.publishment.topic.PublishmentTopic;
import ak.dev.khi_backend.khi_app.repository.publishment.sound.SoundTrackLogRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.sound.SoundTrackRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.topic.PublishmentTopicRepository;
import ak.dev.khi_backend.khi_app.service.S3Service;
import ak.dev.khi_backend.khi_app.service.media.TiptapHtmlProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SoundTrackService {

    private static final String TOPIC_ENTITY_TYPE = "SOUND";

    private static final String SOUND_REKLAM_VIDEO_NOT_FOUND = "sound.reklamVideo.not_found";

    private static final String SOUND_REKLAM_VIDEO_ALREADY_EXISTS = "sound.reklamVideo.already_exists";

    private final SoundTrackRepository       soundTrackRepository;
    private final SoundReklamVideoRepository soundReklamVideoRepository;
    private final SoundTrackLogRepository    soundTrackLogRepository;
    private final PublishmentTopicRepository topicRepository;
    private final SoundTrackTypeService      soundTrackTypeService;
    private final S3Service                  s3Service;
    private final TiptapHtmlProcessor        tiptapHtmlProcessor;
    private final ak.dev.khi_backend.khi_app.service.media.MediaMetadataService mediaMetadata;

    // =========================================================================
    // دروستکردن (CREATE)
    // =========================================================================

    @CacheEvict(value = "soundTracks", allEntries = true)
    @Transactional
    public Response create(
            CreateRequest        dto,
            MultipartFile        ckbCoverImage,
            MultipartFile        kmrCoverImage,
            MultipartFile        hoverCoverImage,
            List<MultipartFile>  audioFiles,
            List<MultipartFile>  brochureFiles,
            List<MultipartFile>  attachmentFiles,
            List<MultipartFile>  albumBrochureFiles
    ) {
        validateCreate(dto);

        try {
            String ckbCoverUrl   = resolveCoverUrl(dto.getCkbCoverUrl(), ckbCoverImage);
            String kmrCoverUrl   = resolveCoverUrl(dto.getKmrCoverUrl(), kmrCoverImage);
            String hoverCoverUrl = resolveCoverUrl(dto.getHoverCoverUrl(), hoverCoverImage);

            Set<String> soundTypes = mergedTypeSet(dto.getSoundTypes(), dto.getSoundType());
            if (soundTypes.isEmpty())
                throw Errors.soundValidation("soundTrack.soundType.required",
                        Map.of("field", "soundType"));
            String primaryType = soundTypes.iterator().next();

            Set<PublishmentTopic> topics = resolveOrCreateTopics(
                    dto.getTopicIds(), dto.getNewTopics(), dto.getTopicId(), dto.getNewTopic());

            SoundTrack entity = SoundTrack.builder()
                    .ckbCoverUrl(ckbCoverUrl)
                    .kmrCoverUrl(kmrCoverUrl)
                    .hoverCoverUrl(hoverCoverUrl)
                    .soundType(primaryType)
                    .soundTypes(new LinkedHashSet<>(soundTypes))
                    .trackState(dto.getTrackState())
                    .albumOfMemories(dto.getAlbumOfMemories() != null && dto.getAlbumOfMemories())
                    .topics(topics)
                    .contentLanguages(new LinkedHashSet<>(safeLangs(dto.getContentLanguages())))
                    .reader(trimOrNull(dto.getReader()))
                    .directors(new LinkedHashSet<>(cleanStrings(dto.getDirectors())))
                    .terms(trimOrNull(dto.getTerms()))
                    .locations(new LinkedHashSet<>(cleanStrings(dto.getLocations())))
                    .thisProjectOfInstitute(dto.isThisProjectOfInstitute())
                    .tagsCkb(new LinkedHashSet<>(cleanStrings(
                            dto.getTags() != null ? dto.getTags().getCkb() : null)))
                    .tagsKmr(new LinkedHashSet<>(cleanStrings(
                            dto.getTags() != null ? dto.getTags().getKmr() : null)))
                    .keywordsCkb(new LinkedHashSet<>(cleanStrings(
                            dto.getKeywords() != null ? dto.getKeywords().getCkb() : null)))
                    .keywordsKmr(new LinkedHashSet<>(cleanStrings(
                            dto.getKeywords() != null ? dto.getKeywords().getKmr() : null)))
                    .albumName(trimOrNull(dto.getAlbumName()))
                    .publishmentYear(dto.getPublishmentYear())
                    .cdNumber(dto.getCdNumber())
                    .totalTracks(dto.getTotalTracks())
                    .build();

            applyContentByLanguages(entity,
                    dto.getContentLanguages(), dto.getCkbContent(), dto.getKmrContent());

            buildAndAttachFiles(entity, dto.getFiles(), audioFiles, brochureFiles);
            buildAndAttachAttachments(entity, dto.getAttachments(), attachmentFiles);
            buildAndAttachAlbumBrochures(entity, dto.getBrochures(), albumBrochureFiles);

            mediaMetadata.safely(() -> mediaMetadata.applyToSoundTrack(entity));
            SoundTrack saved = soundTrackRepository.save(entity);
            saved.getSoundTypes().forEach(soundTrackTypeService::ensureExists);

            createLog(saved.getId(), titleOf(saved), "CREATED",
                    "سەدا دروستکرا — جۆر=" + saved.getSoundType()
                            + " دۆخ=" + saved.getTrackState()
                            + (topics.isEmpty() ? "" : " بابەت=" + topics.size()));

            return toResponse(saved);

        } catch (IOException e) {
            throw Errors.soundStorageFailed("sound.media_upload_failed",
                    Map.of("reason", "کێشە لە ناردنی فایل: " + e.getMessage()), e);
        }
    }

    // =========================================================================
    // نوێکردنەوە (UPDATE)
    // =========================================================================

    @CacheEvict(value = "soundTracks", allEntries = true)
    @Transactional
    public Response update(
            Long                 id,
            UpdateRequest        dto,
            MultipartFile        ckbCoverImage,
            MultipartFile        kmrCoverImage,
            MultipartFile        hoverCoverImage,
            List<MultipartFile>  audioFiles,
            List<MultipartFile>  brochureFiles,
            List<MultipartFile>  attachmentFiles,
            List<MultipartFile>  albumBrochureFiles
    ) {
        if (id == null)
            throw Errors.soundValidation("error.validation",
                    Map.of("field", "id", "message", "ئایدی پێویستە"));

        SoundTrack entity = soundTrackRepository.findByIdWithGraph(id)
                .orElseThrow(() -> Errors.soundNotFound(id));

        boolean updatesFiles = dto.getFiles() != null || hasUploads(audioFiles);
        boolean updatesAttachments = dto.getAttachments() != null || hasUploads(attachmentFiles);
        boolean updatesBrochures = dto.getBrochures() != null || hasUploads(albumBrochureFiles);
        boolean updatesTopics = !dto.isClearTopic()
                && (dto.getTopicIds() != null || dto.getNewTopics() != null
                    || dto.getTopicId() != null || dto.getNewTopic() != null);
        Set<PublishmentTopic> resolvedTopics = updatesTopics
                ? resolveOrCreateTopics(dto.getTopicIds(), dto.getNewTopics(),
                        dto.getTopicId(), dto.getNewTopic())
                : null;

        try {
            // ── Cover images ──────────────────────────────────────────────
            if (hasFile(ckbCoverImage) || dto.getCkbCoverUrl() != null) {
                entity.setCkbCoverUrl(resolveCoverUrl(dto.getCkbCoverUrl(), ckbCoverImage));
            }
            if (hasFile(kmrCoverImage) || dto.getKmrCoverUrl() != null) {
                entity.setKmrCoverUrl(resolveCoverUrl(dto.getKmrCoverUrl(), kmrCoverImage));
            }
            if (hasFile(hoverCoverImage) || dto.getHoverCoverUrl() != null) {
                entity.setHoverCoverUrl(resolveCoverUrl(dto.getHoverCoverUrl(), hoverCoverImage));
            }

            // ── Core ──────────────────────────────────────────────────────
            if (dto.getSoundTypes() != null || !isBlank(dto.getSoundType())) {
                Set<String> types = mergedTypeSet(dto.getSoundTypes(), dto.getSoundType());
                if (types.isEmpty())
                    throw Errors.soundValidation("soundTrack.soundType.required",
                            Map.of("field", "soundType"));
                entity.getSoundTypes().clear();
                entity.getSoundTypes().addAll(types);
                entity.setSoundType(types.iterator().next());
            }
            if (dto.getTrackState()      != null)  entity.setTrackState(dto.getTrackState());
            if (dto.getAlbumOfMemories() != null)  entity.setAlbumOfMemories(dto.getAlbumOfMemories());

            // ── Topics ────────────────────────────────────────────────────
            if (dto.isClearTopic()) {
                entity.getTopics().clear();
            } else if (updatesTopics) {
                entity.getTopics().clear();
                entity.getTopics().addAll(resolvedTopics);
            }

            // ── Languages ─────────────────────────────────────────────────
            if (dto.getContentLanguages() != null) {
                entity.getContentLanguages().clear();
                entity.getContentLanguages().addAll(safeLangs(dto.getContentLanguages()));
            }
            applyContentForUpdate(entity,
                    entity.getContentLanguages(), dto.getCkbContent(), dto.getKmrContent());

            // ── Locations ─────────────────────────────────────────────────
            if (dto.getLocations() != null) {
                entity.getLocations().clear();
                entity.getLocations().addAll(cleanStrings(dto.getLocations()));
            }

            // ── Reader (single field) ─────────────────────────────────────
            if (dto.getReader() != null) {
                entity.setReader(trimOrNull(dto.getReader()));
            }

            // ── Directors ─────────────────────────────────────────────────
            if (dto.getDirectors() != null) {
                entity.getDirectors().clear();
                entity.getDirectors().addAll(cleanStrings(dto.getDirectors()));
            }

            // ── Terms ─────────────────────────────────────────────────────
            if (dto.getTerms() != null) entity.setTerms(trimOrNull(dto.getTerms()));

            // ── Institute ─────────────────────────────────────────────────
            if (dto.getThisProjectOfInstitute() != null)
                entity.setThisProjectOfInstitute(dto.getThisProjectOfInstitute());

            // ── Tags & Keywords ───────────────────────────────────────────
            if (dto.getTags() != null) {
                if (dto.getTags().getCkb() != null) {
                    entity.getTagsCkb().clear();
                    entity.getTagsCkb().addAll(cleanStrings(dto.getTags().getCkb()));
                }
                if (dto.getTags().getKmr() != null) {
                    entity.getTagsKmr().clear();
                    entity.getTagsKmr().addAll(cleanStrings(dto.getTags().getKmr()));
                }
            }
            if (dto.getKeywords() != null) {
                if (dto.getKeywords().getCkb() != null) {
                    entity.getKeywordsCkb().clear();
                    entity.getKeywordsCkb().addAll(cleanStrings(dto.getKeywords().getCkb()));
                }
                if (dto.getKeywords().getKmr() != null) {
                    entity.getKeywordsKmr().clear();
                    entity.getKeywordsKmr().addAll(cleanStrings(dto.getKeywords().getKmr()));
                }
            }

            // ── Audio Files ───────────────────────────────────────────────
            // FIX: Guard against null collection before calling .clear().
            // The frontend always sends a files array, so hasFileDtos is always
            // true. If the entity was persisted without any files the collection
            // may be null depending on fetch graph / lazy init state.
            boolean hasFileDtos     = dto.getFiles() != null;
            boolean hasAudioUploads = audioFiles != null
                    && audioFiles.stream().anyMatch(f -> f != null && !f.isEmpty());

            if (hasFileDtos || hasAudioUploads) {
                if (entity.getFiles() == null) {
                    entity.setFiles(new LinkedHashSet<>());
                }
                Set<SoundTrackFile> mergedFiles = mergeFiles(
                        entity, dto.getFiles(), audioFiles, brochureFiles);
                entity.getFiles().clear();
                entity.getFiles().addAll(mergedFiles);
            }

            // ── Multi-Album Fields ────────────────────────────────────────
            if (dto.getAlbumName()       != null) entity.setAlbumName(trimOrNull(dto.getAlbumName()));
            if (dto.getPublishmentYear() != null) entity.setPublishmentYear(dto.getPublishmentYear());
            if (dto.getCdNumber()        != null) entity.setCdNumber(dto.getCdNumber());
            if (dto.getTotalTracks()     != null) entity.setTotalTracks(dto.getTotalTracks());

            // ── Attachments (available for SINGLE and MULTI) ─────────────
            // FIX: Same null-safe guard as for files above.
            boolean hasAttachDtos    = dto.getAttachments() != null;
            boolean hasAttachUploads = attachmentFiles != null
                    && attachmentFiles.stream().anyMatch(f -> f != null && !f.isEmpty());

            if (hasAttachDtos || hasAttachUploads) {
                if (entity.getAttachments() == null) {
                    entity.setAttachments(new LinkedHashSet<>());
                }
                Set<SoundTrackAttachment> mergedAttachments = mergeAttachments(
                        entity, dto.getAttachments(), attachmentFiles);
                entity.getAttachments().clear();
                entity.getAttachments().addAll(mergedAttachments);
            }

            // ── Album brochures (independent of the sound files) ─────────
            if (updatesBrochures) {
                if (entity.getBrochures() == null) {
                    entity.setBrochures(new LinkedHashSet<>());
                }
                Set<SoundAlbumBrochure> mergedBrochures = mergeAlbumBrochures(
                        entity, dto.getBrochures(), albumBrochureFiles);
                entity.getBrochures().clear();
                entity.getBrochures().addAll(mergedBrochures);
            }

            mediaMetadata.safely(() -> mediaMetadata.applyToSoundTrack(entity));
            SoundTrack saved = soundTrackRepository.save(entity);
            saved.getSoundTypes().forEach(soundTrackTypeService::ensureExists);

            createLog(saved.getId(), titleOf(saved), "UPDATED",
                    "سەدا نوێکرایەوە — جۆر=" + saved.getSoundType()
                            + " دۆخ=" + saved.getTrackState());

            return toResponse(saved);

        } catch (IOException e) {
            throw Errors.soundStorageFailed("sound.media_upload_failed",
                    Map.of("reason", "کێشە لە ناردنی فایل: " + e.getMessage()), e);
        }
    }

    // =========================================================================
    // READ
    // =========================================================================

    @Cacheable(value = "soundTracks", key = "'all:p' + #page + ':s' + #size")
    @Transactional(readOnly = true)
    public Page<Response> getAll(int page, int size) {
        return hydratePage(soundTrackRepository.findAllIds(PageRequest.of(page, size)));
    }

    @Transactional(readOnly = true)
    public Page<Response> getFeatured(int page, int size) {
        Pageable pageable = featuredPageable(page, size);
        return soundTrackRepository.findByFeaturedTrue(pageable).map(this::toResponse);
    }

    @Cacheable(value = "soundTracks",
            key = "'state:' + #state.name() + ':p' + #page + ':s' + #size")
    @Transactional(readOnly = true)
    public Page<Response> getByState(TrackState state, int page, int size) {
        if (state == null)
            throw Errors.soundValidation("soundTrack.state.required", Map.of("field", "state"));
        return hydratePage(soundTrackRepository.findIdsByState(state, PageRequest.of(page, size)));
    }

    @Cacheable(value = "soundTracks",
            key = "'soundType:' + #soundType.toLowerCase() + ':p' + #page + ':s' + #size")
    @Transactional(readOnly = true)
    public Page<Response> getBySoundType(String soundType, int page, int size) {
        if (isBlank(soundType))
            throw Errors.soundValidation("soundTrack.soundType.required",
                    Map.of("field", "soundType"));
        return hydratePage(soundTrackRepository.findIdsBySoundType(
                soundType.trim(), PageRequest.of(page, size)));
    }

    @Cacheable(value = "soundTracks",
            key = "'topic:' + #topicId + ':p' + #page + ':s' + #size")
    @Transactional(readOnly = true)
    public Page<Response> getByTopic(Long topicId, int page, int size) {
        if (topicId == null)
            throw Errors.soundValidation("error.validation", Map.of("field", "topicId"));
        return hydratePage(soundTrackRepository.findIdsByTopic(
                topicId, PageRequest.of(page, size)));
    }

    @Cacheable(value = "soundTracks", key = "'album:p' + #page + ':s' + #size")
    @Transactional(readOnly = true)
    public Page<Response> getAlbumOfMemories(int page, int size) {
        return hydratePage(soundTrackRepository.findIdsAlbumOfMemories(PageRequest.of(page, size)));
    }

    @Cacheable(value = "soundTracks",
            key = "'tag:' + #tag.toLowerCase() + ':p' + #page + ':s' + #size")
    @Transactional(readOnly = true)
    public Page<Response> searchByTag(String tag, int page, int size) {
        if (isBlank(tag))
            throw Errors.badRequest("tag.required", Map.of("field", "tag"));
        return hydratePage(soundTrackRepository.findIdsByTag(tag.trim(), PageRequest.of(page, size)));
    }

    @Cacheable(value = "soundTracks",
            key = "'kw:' + #keyword.toLowerCase() + ':p' + #page + ':s' + #size")
    @Transactional(readOnly = true)
    public Page<Response> searchByKeyword(String keyword, int page, int size) {
        if (isBlank(keyword))
            throw Errors.badRequest("keyword.required", Map.of("field", "keyword"));
        return hydratePage(soundTrackRepository.findIdsByKeyword(
                keyword.trim(), PageRequest.of(page, size)));
    }

    @Cacheable(value = "soundTracks",
            key = "'search:' + #q.toLowerCase() + ':p' + #page + ':s' + #size")
    @Transactional(readOnly = true)
    public Page<Response> globalSearch(String q, int page, int size) {
        if (isBlank(q))
            throw Errors.badRequest("keyword.required", Map.of("field", "q"));
        return hydratePage(soundTrackRepository.findIdsByGlobalSearch(
                q.trim(), PageRequest.of(page, size)));
    }

    @Transactional(readOnly = true)
    public Response getById(Long id) {
        return toResponse(soundTrackRepository.findByIdWithGraph(id)
                .orElseThrow(() -> Errors.soundNotFound(id)));
    }

    @CacheEvict(value = "soundTracks", allEntries = true)
    @Transactional
    public SoundReklamVideoResponse createSoundReklamVideo(MultipartFile videoFile) {
        validatePromoVideoFile(videoFile);

        if (soundReklamVideoRepository.findTopByOrderByIdAsc().isPresent()) {
            throw Errors.soundValidation(SOUND_REKLAM_VIDEO_ALREADY_EXISTS, Map.of());
        }

        try {
            SoundReklamVideo created = soundReklamVideoRepository.save(
                    SoundReklamVideo.builder()
                            .videoUrl(uploadFile(videoFile))
                            .sizeBytes(videoFile.getSize())
                            .mimeType(trimOrNull(videoFile.getContentType()))
                            .build()
            );

            return toSoundReklamVideoResponse(created);
        } catch (IOException e) {
            throw Errors.soundStorageFailed("sound.media_upload_failed",
                    Map.of("reason", "کێشە لە ناردنی فایل: " + e.getMessage()), e);
        }
    }

    @Transactional(readOnly = true)
    public SoundReklamVideoResponse getSoundReklamVideo() {
        return toSoundReklamVideoResponse(findSoundReklamVideoOrThrow());
    }

    @CacheEvict(value = "soundTracks", allEntries = true)
    @Transactional
    public SoundReklamVideoResponse updateSoundReklamVideo(MultipartFile videoFile) {
        validatePromoVideoFile(videoFile);

        SoundReklamVideo reklamVideo = findSoundReklamVideoOrThrow();
        String previousVideoUrl = reklamVideo.getVideoUrl();

        try {
            reklamVideo.setVideoUrl(uploadFile(videoFile));
            reklamVideo.setSizeBytes(videoFile.getSize());
            reklamVideo.setMimeType(trimOrNull(videoFile.getContentType()));

            SoundReklamVideo saved = soundReklamVideoRepository.save(reklamVideo);
            if (!isBlank(previousVideoUrl)) {
                s3Service.deleteFile(previousVideoUrl);
            }

            return toSoundReklamVideoResponse(saved);
        } catch (IOException e) {
            throw Errors.soundStorageFailed("sound.media_upload_failed",
                    Map.of("reason", "کێشە لە ناردنی فایل: " + e.getMessage()), e);
        }
    }

    @CacheEvict(value = "soundTracks", allEntries = true)
    @Transactional
    public void deleteSoundReklamVideo() {
        SoundReklamVideo reklamVideo = findSoundReklamVideoOrThrow();
        String videoUrl = reklamVideo.getVideoUrl();

        soundReklamVideoRepository.delete(reklamVideo);
        if (!isBlank(videoUrl)) {
            s3Service.deleteFile(videoUrl);
        }
    }

    // =========================================================================
    // سڕینەوە (DELETE)
    // =========================================================================

    @CacheEvict(value = "soundTracks", allEntries = true)
    @Transactional
    public void delete(Long id) {
        if (id == null) return;

        SoundTrack entity = soundTrackRepository.findByIdWithGraph(id).orElse(null);
        if (entity == null) {
            log.debug("SoundTrack delete ignored; id={} does not exist", id);
            return;
        }
        createLog(entity.getId(), titleOf(entity), "DELETED",
                "سەدا سڕایەوە — جۆر=" + entity.getSoundType()
                        + " دۆخ=" + entity.getTrackState());
        soundTrackRepository.delete(entity);

        // Remove every file this track stored in S3
        List<String> urls = new ArrayList<>();
        urls.add(entity.getCkbCoverUrl());
        urls.add(entity.getKmrCoverUrl());
        urls.add(entity.getHoverCoverUrl());
        urls.add(entity.getFeatureImageUrl());
        if (entity.getFiles() != null)
            entity.getFiles().forEach(f -> { if (f != null) urls.add(f.getFileUrl()); });
        if (entity.getAttachments() != null)
            entity.getAttachments().forEach(a -> { if (a != null) urls.add(a.getFileUrl()); });
        s3Service.deleteFiles(urls);
    }

    /**
     * Bulk list-order update (dashboard drag & drop).
     * orderedIds[i] gets sortOrder = i; unlisted ids keep their current value
     * and still render after the ordered ones (nulls last).
     */
    @CacheEvict(value = "soundTracks", allEntries = true)
    @Transactional
    public void reorder(List<Long> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) return;
        List<SoundTrack> tracks = soundTrackRepository.findAllById(orderedIds);
        Map<Long, Integer> positions = new HashMap<>();
        for (int i = 0; i < orderedIds.size(); i++) {
            if (orderedIds.get(i) != null) positions.put(orderedIds.get(i), i);
        }
        for (SoundTrack track : tracks) {
            Integer position = positions.get(track.getId());
            if (position != null) track.setSortOrder(position);
        }
        soundTrackRepository.saveAll(tracks);
    }

    // =========================================================================
    // HYDRATION
    // =========================================================================

    private Page<Response> hydratePage(Page<Long> idPage) {
        if (idPage.isEmpty())
            return new PageImpl<>(Collections.emptyList(),
                    idPage.getPageable(), idPage.getTotalElements());
        return new PageImpl<>(
                hydrateAndSort(idPage.getContent()).stream()
                        .map(this::toResponse)
                        .collect(Collectors.toList()),
                idPage.getPageable(),
                idPage.getTotalElements());
    }

    private List<SoundTrack> hydrateAndSort(List<Long> ids) {
        List<SoundTrack> rows = soundTrackRepository.findAllByIds(ids);
        Map<Long, SoundTrack> indexed = new LinkedHashMap<>(rows.size());
        for (SoundTrack s : rows) indexed.put(s.getId(), s);
        List<SoundTrack> ordered = new ArrayList<>(ids.size());
        for (Long id : ids) { SoundTrack s = indexed.get(id); if (s != null) ordered.add(s); }
        return ordered;
    }

    // =========================================================================
    // FILE BUILDER
    // =========================================================================

    private void buildAndAttachFiles(
            SoundTrack              owner,
            List<FileCreateRequest> fileDtos,
            List<MultipartFile>     audioFiles,
            List<MultipartFile>     brochureFiles
    ) throws IOException {

        int dtoCount   = fileDtos   == null ? 0 : fileDtos.size();
        int audioCount = audioFiles == null ? 0
                : (int) audioFiles.stream().filter(f -> f != null && !f.isEmpty()).count();
        int total      = Math.max(dtoCount, audioCount);
        if (total == 0) return;

        int audioIdx    = 0;
        int brochureIdx = 0;

        for (int i = 0; i < total; i++) {
            FileCreateRequest fDto = (fileDtos != null && i < fileDtos.size())
                    ? fileDtos.get(i) : null;

            MultipartFile audioFile = nextNonEmpty(audioFiles, audioIdx);
            if (audioFile != null) audioIdx = advanceIndex(audioFiles, audioIdx);

            String fileUrl     = null;
            String externalUrl = fDto != null ? trimOrNull(fDto.getExternalUrl()) : null;
            String embedUrl    = fDto != null ? trimOrNull(fDto.getEmbedUrl())    : null;
            long   sizeBytes   = fDto != null ? fDto.getSizeBytes()               : 0L;
            long   durationSec = fDto != null ? fDto.getDurationSeconds()         : 0L;

            if (audioFile != null) {
                fileUrl   = uploadFile(audioFile);
                sizeBytes = audioFile.getSize();
            } else if (fDto != null) {
                fileUrl = trimOrNull(fDto.getFileUrl());
            }

            if (isBlank(fileUrl) && isBlank(externalUrl) && isBlank(embedUrl))
                continue; // file row with no source — skip it, keep the save

            String[] fileTitle = mergeBilingual(null, null, null,
                    fDto != null ? fDto.getTitle() : null,
                    fDto != null ? fDto.getTitleCkb() : null,
                    fDto != null ? fDto.getTitleKmr() : null);
            SoundTrackFile file = SoundTrackFile.builder()
                    .fileUrl(fileUrl)
                    .externalUrl(externalUrl)
                    .embedUrl(embedUrl)
                    .title(fileTitle[0]).titleCkb(fileTitle[1]).titleKmr(fileTitle[2])
                    .fileType(fDto != null && fDto.getFileType() != null
                            ? fDto.getFileType()
                            : FileType.AUDIO)
                    .publishmentYear(fDto != null ? fDto.getPublishmentYear()  : null)
                    .sizeBytes(sizeBytes)
                    .durationSeconds(durationSec)
                    .bitRate(fDto != null ? trimOrNull(fDto.getBitRate())      : null)
                    .sampleRate(fDto != null ? trimOrNull(fDto.getSampleRate()): null)
                    .audioChannel(fDto != null ? fDto.getAudioChannel()        : null)
                    .form(fDto != null ? trimOrNull(fDto.getForm())            : null)
                    .genre(fDto != null ? trimOrNull(fDto.getGenre())          : null)
                    .recordingVenue(fDto != null ? trimOrNull(fDto.getRecordingVenue()) : null)
                    .sortOrder(fDto != null && fDto.getSortOrder() != null
                            ? fDto.getSortOrder() : i)
                    .build();

            owner.addFile(file);

            List<BrochureRequest> brochureDtos = (fDto != null && fDto.getBrochures() != null)
                    ? fDto.getBrochures() : Collections.emptyList();
            brochureIdx = buildAndAttachBrochures(file, brochureDtos, brochureFiles, brochureIdx);
        }
    }

    private Set<SoundTrackFile> mergeFiles(
            SoundTrack owner,
            List<FileCreateRequest> fileDtos,
            List<MultipartFile> audioFiles,
            List<MultipartFile> brochureFiles
    ) throws IOException {
        Set<SoundTrackFile> existingFiles = owner.getFiles() == null
                ? Set.of()
                : owner.getFiles();
        Map<Long, SoundTrackFile> existingById = existingFiles.stream()
                .filter(Objects::nonNull)
                .filter(file -> file.getId() != null)
                .collect(Collectors.toMap(
                        SoundTrackFile::getId,
                        file -> file,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
        Map<String, SoundTrackFile> existingBySource = new LinkedHashMap<>();
        for (SoundTrackFile f : existingFiles) {
            if (f == null) continue;
            for (String u : new String[]{f.getFileUrl(), f.getExternalUrl(), f.getEmbedUrl()}) {
                if (!isBlank(u)) existingBySource.putIfAbsent(u.trim(), f);
            }
        }
        Set<Long> requestedIds = new HashSet<>();
        LinkedHashSet<SoundTrackFile> merged = new LinkedHashSet<>();
        int dtoCount = fileDtos == null ? 0 : fileDtos.size();
        int fileCount = nonEmptyFileCount(audioFiles);
        int total = Math.max(dtoCount, fileCount);
        int audioIndex = 0;
        int[] brochureIndex = {0};

        for (int i = 0; i < total; i++) {
            FileCreateRequest dto = fileDtos != null && i < fileDtos.size()
                    ? fileDtos.get(i)
                    : null;
            MultipartFile upload = nextNonEmpty(audioFiles, audioIndex);
            if (upload != null) audioIndex = advanceIndex(audioFiles, audioIndex);

            SoundTrackFile file = resolveExistingFile(
                    existingById, existingBySource, requestedIds, dto);
            boolean isNew = file == null;
            if (isNew) {
                file = new SoundTrackFile();
                file.setSoundTrack(owner);
            }

            // Entry with neither an upload nor a URL source keeps whatever it had
            // (or, when new, is dropped entirely) instead of blocking the save.
            if (!hasFile(upload) && !hasSoundSource(dto)
                    && (isNew || !hasSoundSource(file))) {
                if (isNew) {
                    requestedIds.remove(null);
                    continue;
                }
            }

            if (hasFile(upload)) {
                file.setFileUrl(uploadFile(upload));
                file.setExternalUrl(null);
                file.setEmbedUrl(null);
                file.setSizeBytes(upload.getSize());
            } else if (hasSoundSource(dto)) {
                file.setFileUrl(trimOrNull(dto.getFileUrl()));
                file.setExternalUrl(trimOrNull(dto.getExternalUrl()));
                file.setEmbedUrl(trimOrNull(dto.getEmbedUrl()));
            }

            applyFileMetadata(file, dto, isNew, hasFile(upload));
            file.setSortOrder(dto != null && dto.getSortOrder() != null
                    ? dto.getSortOrder() : i);
            if (dto != null && dto.getBrochures() != null) {
                List<SoundTrackBrochure> brochures = mergeBrochures(
                        file, dto.getBrochures(), brochureFiles, brochureIndex);
                file.getBrochures().clear();
                file.getBrochures().addAll(brochures);
            }
            merged.add(file);
        }

        return merged;
    }

    private void applyFileMetadata(
            SoundTrackFile file,
            FileCreateRequest dto,
            boolean isNew,
            boolean hasUpload
    ) {
        if (dto == null) return;
        String[] t = mergeBilingual(file.getTitle(), file.getTitleCkb(), file.getTitleKmr(),
                dto.getTitle(), dto.getTitleCkb(), dto.getTitleKmr());
        file.setTitle(t[0]);
        file.setTitleCkb(t[1]);
        file.setTitleKmr(t[2]);
        if (dto.getFileType() != null) file.setFileType(dto.getFileType());
        if (isNew && file.getFileType() == null)
            file.setFileType(FileType.AUDIO);
        if (dto.getPublishmentYear() != null) file.setPublishmentYear(dto.getPublishmentYear());
        if (!hasUpload && (isNew || dto.getSizeBytes() != 0)) {
            file.setSizeBytes(dto.getSizeBytes());
        }
        if (isNew || dto.getDurationSeconds() != 0) {
            file.setDurationSeconds(dto.getDurationSeconds());
        }
        if (dto.getBitRate() != null) file.setBitRate(trimOrNull(dto.getBitRate()));
        if (dto.getSampleRate() != null) file.setSampleRate(trimOrNull(dto.getSampleRate()));
        if (dto.getAudioChannel() != null) file.setAudioChannel(dto.getAudioChannel());
        if (dto.getForm() != null) file.setForm(trimOrNull(dto.getForm()));
        if (dto.getGenre() != null) file.setGenre(trimOrNull(dto.getGenre()));
        if (dto.getRecordingVenue() != null) {
            file.setRecordingVenue(trimOrNull(dto.getRecordingVenue()));
        }
    }

    private List<SoundTrackBrochure> mergeBrochures(
            SoundTrackFile owner,
            List<BrochureRequest> dtos,
            List<MultipartFile> uploads,
            int[] uploadIndex
    ) throws IOException {
        Map<Long, SoundTrackBrochure> existingById = owner.getBrochures().stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getId() != null)
                .collect(Collectors.toMap(
                        SoundTrackBrochure::getId,
                        item -> item,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
        Map<String, SoundTrackBrochure> existingBySource =
                indexBySources(owner.getBrochures(), SoundTrackBrochure::getImageUrl);
        Set<Long> requestedIds = new HashSet<>();
        List<SoundTrackBrochure> merged = new ArrayList<>();

        for (int i = 0; i < dtos.size(); i++) {
            BrochureRequest dto = dtos.get(i);
            if (dto == null) continue;

            MultipartFile upload = nextNonEmpty(uploads, uploadIndex[0]);
            if (upload != null) uploadIndex[0] = advanceIndex(uploads, uploadIndex[0]);

            SoundTrackBrochure brochure = resolveExistingBrochure(
                    existingById, existingBySource, requestedIds, dto);
            if (brochure == null) {
                if (!hasFile(upload) && isBlank(dto.getImageUrl())) continue;
                brochure = new SoundTrackBrochure();
                brochure.setSoundTrackFile(owner);
            }

            if (hasFile(upload)) {
                brochure.setImageUrl(uploadFile(upload));
            } else if (!isBlank(dto.getImageUrl())) {
                brochure.setImageUrl(dto.getImageUrl().trim());
            }
            String[] cap = mergeBilingual(brochure.getCaption(), brochure.getCaptionCkb(),
                    brochure.getCaptionKmr(), dto.getCaption(), dto.getCaptionCkb(), dto.getCaptionKmr());
            brochure.setCaption(cap[0]);
            brochure.setCaptionCkb(cap[1]);
            brochure.setCaptionKmr(cap[2]);
            brochure.setBrochureOrder(i);
            merged.add(brochure);
        }
        return merged;
    }

    private Set<SoundTrackAttachment> mergeAttachments(
            SoundTrack owner,
            List<AttachmentRequest> dtos,
            List<MultipartFile> uploads
    ) throws IOException {
        Set<SoundTrackAttachment> existingAttachments = owner.getAttachments() == null
                ? Set.of()
                : owner.getAttachments();
        Map<Long, SoundTrackAttachment> existingById = existingAttachments.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getId() != null)
                .collect(Collectors.toMap(
                        SoundTrackAttachment::getId,
                        item -> item,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
        Map<String, SoundTrackAttachment> existingBySource =
                indexBySources(existingAttachments, SoundTrackAttachment::getFileUrl);
        Set<Long> requestedIds = new HashSet<>();
        LinkedHashSet<SoundTrackAttachment> merged = new LinkedHashSet<>();
        int dtoCount = dtos == null ? 0 : dtos.size();
        int fileCount = nonEmptyFileCount(uploads);
        int total = Math.max(dtoCount, fileCount);
        int uploadIndex = 0;

        for (int i = 0; i < total; i++) {
            AttachmentRequest dto = dtos != null && i < dtos.size() ? dtos.get(i) : null;
            MultipartFile upload = nextNonEmpty(uploads, uploadIndex);
            if (upload != null) uploadIndex = advanceIndex(uploads, uploadIndex);

            SoundTrackAttachment attachment = resolveExistingAttachment(
                    existingById, existingBySource, requestedIds, dto);
            boolean isNew = attachment == null;
            if (isNew) {
                if (!hasFile(upload) && (dto == null || isBlank(dto.getFileUrl()))) continue;
                attachment = new SoundTrackAttachment();
                attachment.setSoundTrack(owner);
            }

            if (hasFile(upload)) {
                attachment.setFileUrl(uploadFile(upload));
                attachment.setSizeBytes(upload.getSize());
            } else if (dto != null && !isBlank(dto.getFileUrl())) {
                attachment.setFileUrl(dto.getFileUrl().trim());
            }
            if (dto != null) {
                String[] t = mergeBilingual(attachment.getTitle(), attachment.getTitleCkb(),
                        attachment.getTitleKmr(), dto.getTitle(), dto.getTitleCkb(), dto.getTitleKmr());
                attachment.setTitle(t[0]);
                attachment.setTitleCkb(t[1]);
                attachment.setTitleKmr(t[2]);
                if (dto.getAttachmentType() != null) {
                    attachment.setAttachmentType(dto.getAttachmentType());
                } else if (isNew) {
                    attachment.setAttachmentType(AttachmentType.OTHER);
                }
                if (!hasFile(upload) && (isNew || dto.getSizeBytes() != 0)) {
                    attachment.setSizeBytes(dto.getSizeBytes());
                }
                if (dto.getMimeType() != null) {
                    attachment.setMimeType(trimOrNull(dto.getMimeType()));
                }
            }
            attachment.setAttachmentOrder(dto != null && dto.getAttachmentOrder() != null
                    ? dto.getAttachmentOrder() : i);
            merged.add(attachment);
        }
        return merged;
    }

    private SoundTrackFile resolveExistingFile(
            Map<Long, SoundTrackFile> existingById,
            Map<String, SoundTrackFile> existingBySource,
            Set<Long> requestedIds,
            FileCreateRequest dto
    ) {
        if (dto == null) return null;
        if (dto.getId() != null) {
            SoundTrackFile existing = existingById.get(dto.getId());
            if (existing != null && requestedIds.add(dto.getId())) {
                return existing;
            }
        }
        // Lenient merge — a stale/unknown id (row recreated by an earlier save
        // or a stale editor tab) must not reject the whole PUT with a 400.
        // Match the row by its source URL so repeat saves stay idempotent;
        // no match means the payload row is a genuinely new file.
        SoundTrackFile bySource = firstByUrls(existingBySource,
                dto.getFileUrl(), dto.getExternalUrl(), dto.getEmbedUrl());
        if (bySource != null && bySource.getId() != null
                && requestedIds.add(bySource.getId())) {
            return bySource;
        }
        return null;
    }

    private SoundTrackBrochure resolveExistingBrochure(
            Map<Long, SoundTrackBrochure> existingById,
            Map<String, SoundTrackBrochure> existingBySource,
            Set<Long> requestedIds,
            BrochureRequest dto
    ) {
        if (dto.getId() != null) {
            SoundTrackBrochure existing = existingById.get(dto.getId());
            if (existing != null && requestedIds.add(dto.getId())) {
                return existing;
            }
        }
        SoundTrackBrochure bySource = firstByUrls(existingBySource, dto.getImageUrl());
        if (bySource != null && bySource.getId() != null
                && requestedIds.add(bySource.getId())) {
            return bySource;
        }
        return null;
    }

    private SoundTrackAttachment resolveExistingAttachment(
            Map<Long, SoundTrackAttachment> existingById,
            Map<String, SoundTrackAttachment> existingBySource,
            Set<Long> requestedIds,
            AttachmentRequest dto
    ) {
        if (dto == null) return null;
        if (dto.getId() != null) {
            SoundTrackAttachment existing = existingById.get(dto.getId());
            if (existing != null && requestedIds.add(dto.getId())) {
                return existing;
            }
        }
        SoundTrackAttachment bySource = firstByUrls(existingBySource, dto.getFileUrl());
        if (bySource != null && bySource.getId() != null
                && requestedIds.add(bySource.getId())) {
            return bySource;
        }
        return null;
    }

    /** Index existing nested rows by each non-blank source URL they carry. */
    private <T> Map<String, T> indexBySources(
            Collection<T> items,
            Function<T, String> urlOf
    ) {
        Map<String, T> map = new LinkedHashMap<>();
        if (items == null) return map;
        for (T item : items) {
            if (item == null) continue;
            String url = urlOf.apply(item);
            if (!isBlank(url)) map.putIfAbsent(url.trim(), item);
        }
        return map;
    }

    private <T> T firstByUrls(Map<String, T> map, String... urls) {
        if (urls == null) return null;
        for (String u : urls) {
            if (isBlank(u)) continue;
            T hit = map.get(u.trim());
            if (hit != null) return hit;
        }
        return null;
    }

    private boolean hasSoundSource(FileCreateRequest dto) {
        return dto != null && (!isBlank(dto.getFileUrl())
                || !isBlank(dto.getExternalUrl())
                || !isBlank(dto.getEmbedUrl()));
    }

    private boolean hasSoundSource(SoundTrackFile file) {
        return file != null && (!isBlank(file.getFileUrl())
                || !isBlank(file.getExternalUrl())
                || !isBlank(file.getEmbedUrl()));
    }

    // =========================================================================
    // BROCHURE BUILDER
    // =========================================================================

    private int buildAndAttachBrochures(
            SoundTrackFile        file,
            List<BrochureRequest> brochureDtos,
            List<MultipartFile>   brochureFiles,
            int                   startIndex
    ) throws IOException {

        if (brochureDtos == null || brochureDtos.isEmpty()) return startIndex;

        int idx = startIndex;

        for (int b = 0; b < brochureDtos.size(); b++) {
            BrochureRequest bDto = brochureDtos.get(b);
            if (bDto == null) continue;

            MultipartFile bFile = nextNonEmpty(brochureFiles, idx);
            String imageUrl;

            if (bFile != null) {
                imageUrl = uploadFile(bFile);
                idx      = advanceIndex(brochureFiles, idx);
            } else {
                imageUrl = trimOrNull(bDto.getImageUrl());
            }

            // Skip brochures where no image was resolved (url was blank AND
            // no matching file part was provided).
            if (isBlank(imageUrl)) continue;

            String[] cap = mergeBilingual(null, null, null,
                    bDto.getCaption(), bDto.getCaptionCkb(), bDto.getCaptionKmr());
            file.addBrochure(SoundTrackBrochure.builder()
                    .imageUrl(imageUrl)
                    .caption(cap[0]).captionCkb(cap[1]).captionKmr(cap[2])
                    .brochureOrder(b)
                    .build());
        }
        return idx;
    }

    // =========================================================================
    // ATTACHMENT BUILDER  (available for SINGLE and MULTI)
    // =========================================================================

    private void buildAndAttachAttachments(
            SoundTrack              owner,
            List<AttachmentRequest> dtos,
            List<MultipartFile>     attachmentFiles
    ) throws IOException {

        boolean hasDtos    = dtos != null && !dtos.isEmpty();
        boolean hasUploads = attachmentFiles != null
                && attachmentFiles.stream().anyMatch(f -> f != null && !f.isEmpty());

        // Attachments are fully optional — nothing to do if neither provided
        if (!hasDtos && !hasUploads) return;

        int dtoCount  = dtos == null ? 0 : dtos.size();
        int fileCount = attachmentFiles == null ? 0
                : (int) attachmentFiles.stream().filter(f -> f != null && !f.isEmpty()).count();
        int total     = Math.max(dtoCount, fileCount);

        int attachIdx = 0;

        for (int i = 0; i < total; i++) {
            AttachmentRequest aDto = (dtos != null && i < dtos.size()) ? dtos.get(i) : null;

            MultipartFile aFile = nextNonEmpty(attachmentFiles, attachIdx);
            if (aFile != null) attachIdx = advanceIndex(attachmentFiles, attachIdx);

            String fileUrl  = null;
            long   fileSize = aDto != null ? aDto.getSizeBytes() : 0L;

            if (aFile != null) {
                fileUrl  = uploadFile(aFile);
                fileSize = aFile.getSize();
            } else if (aDto != null) {
                fileUrl = trimOrNull(aDto.getFileUrl());
            }

            // Skip entries with no URL — graceful handling
            if (isBlank(fileUrl)) continue;

            // FIX: Default attachmentType to OTHER when the DTO field is null.
            // Previously @NotNull on AttachmentRequest caused a
            // ConstraintViolationException → 500 when the frontend omitted it.
            AttachmentType resolvedType = (aDto != null && aDto.getAttachmentType() != null)
                    ? aDto.getAttachmentType()
                    : AttachmentType.OTHER;

            String[] attTitle = mergeBilingual(null, null, null,
                    aDto != null ? aDto.getTitle() : null,
                    aDto != null ? aDto.getTitleCkb() : null,
                    aDto != null ? aDto.getTitleKmr() : null);
            owner.addAttachment(SoundTrackAttachment.builder()
                    .fileUrl(fileUrl)
                    .title(attTitle[0]).titleCkb(attTitle[1]).titleKmr(attTitle[2])
                    .attachmentType(resolvedType)
                    .sizeBytes(fileSize)
                    .mimeType(aDto != null ? trimOrNull(aDto.getMimeType()) : null)
                    .attachmentOrder(aDto != null && aDto.getAttachmentOrder() != null
                            ? aDto.getAttachmentOrder() : i)
                    .build());
        }
    }

    // =========================================================================
    // ALBUM BROCHURE BUILDER + MERGE  (SINGLE and MULTI)
    // =========================================================================

    private void buildAndAttachAlbumBrochures(
            SoundTrack                  owner,
            List<AlbumBrochureRequest>  dtos,
            List<MultipartFile>         albumBrochureFiles
    ) throws IOException {

        boolean hasDtos    = dtos != null && !dtos.isEmpty();
        boolean hasUploads = albumBrochureFiles != null
                && albumBrochureFiles.stream().anyMatch(f -> f != null && !f.isEmpty());

        if (!hasDtos && !hasUploads) return;

        int dtoCount  = dtos == null ? 0 : dtos.size();
        int fileCount = albumBrochureFiles == null ? 0
                : (int) albumBrochureFiles.stream().filter(f -> f != null && !f.isEmpty()).count();
        int total     = Math.max(dtoCount, fileCount);

        int uploadIdx = 0;

        for (int i = 0; i < total; i++) {
            AlbumBrochureRequest bDto = (dtos != null && i < dtos.size()) ? dtos.get(i) : null;

            MultipartFile bFile = nextNonEmpty(albumBrochureFiles, uploadIdx);
            if (bFile != null) uploadIdx = advanceIndex(albumBrochureFiles, uploadIdx);

            String fileUrl  = null;
            long   fileSize = bDto != null ? bDto.getSizeBytes() : 0L;
            String mimeType = bDto != null ? trimOrNull(bDto.getMimeType()) : null;

            if (bFile != null) {
                fileUrl  = uploadFile(bFile);
                fileSize = bFile.getSize();
                if (mimeType == null) mimeType = trimOrNull(bFile.getContentType());
            } else if (bDto != null) {
                fileUrl = trimOrNull(bDto.getFileUrl());
            }

            if (isBlank(fileUrl)) continue;

            AttachmentType resolvedType = (bDto != null && bDto.getFileType() != null)
                    ? bDto.getFileType()
                    : guessBrochureType(mimeType);

            String[] brTitle = mergeBilingual(null, null, null,
                    bDto != null ? bDto.getTitle() : null,
                    bDto != null ? bDto.getTitleCkb() : null,
                    bDto != null ? bDto.getTitleKmr() : null);
            owner.addBrochure(SoundAlbumBrochure.builder()
                    .fileUrl(fileUrl)
                    .title(brTitle[0]).titleCkb(brTitle[1]).titleKmr(brTitle[2])
                    .fileType(resolvedType)
                    .sizeBytes(fileSize)
                    .mimeType(mimeType)
                    .brochureOrder(bDto != null && bDto.getBrochureOrder() != null
                            ? bDto.getBrochureOrder() : i)
                    .build());
        }
    }

    private Set<SoundAlbumBrochure> mergeAlbumBrochures(
            SoundTrack                  owner,
            List<AlbumBrochureRequest>  dtos,
            List<MultipartFile>         uploads
    ) throws IOException {
        Set<SoundAlbumBrochure> existing = owner.getBrochures() == null
                ? Set.of()
                : owner.getBrochures();
        Map<Long, SoundAlbumBrochure> existingById = existing.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getId() != null)
                .collect(Collectors.toMap(
                        SoundAlbumBrochure::getId,
                        item -> item,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
        Map<String, SoundAlbumBrochure> existingBySource =
                indexBySources(existing, SoundAlbumBrochure::getFileUrl);
        Set<Long> requestedIds = new HashSet<>();
        LinkedHashSet<SoundAlbumBrochure> merged = new LinkedHashSet<>();
        int dtoCount = dtos == null ? 0 : dtos.size();
        int fileCount = nonEmptyFileCount(uploads);
        int total = Math.max(dtoCount, fileCount);
        int uploadIndex = 0;

        for (int i = 0; i < total; i++) {
            AlbumBrochureRequest dto = dtos != null && i < dtos.size() ? dtos.get(i) : null;
            MultipartFile upload = nextNonEmpty(uploads, uploadIndex);
            if (upload != null) uploadIndex = advanceIndex(uploads, uploadIndex);

            SoundAlbumBrochure brochure = resolveExistingAlbumBrochure(
                    existingById, existingBySource, requestedIds, dto);
            boolean isNew = brochure == null;
            if (isNew) {
                if (!hasFile(upload) && (dto == null || isBlank(dto.getFileUrl()))) continue;
                brochure = new SoundAlbumBrochure();
                brochure.setSoundTrack(owner);
            }

            if (hasFile(upload)) {
                brochure.setFileUrl(uploadFile(upload));
                brochure.setSizeBytes(upload.getSize());
                brochure.setMimeType(trimOrNull(upload.getContentType()));
            } else if (dto != null && !isBlank(dto.getFileUrl())) {
                brochure.setFileUrl(dto.getFileUrl().trim());
            }
            if (dto != null) {
                String[] t = mergeBilingual(brochure.getTitle(), brochure.getTitleCkb(),
                        brochure.getTitleKmr(), dto.getTitle(), dto.getTitleCkb(), dto.getTitleKmr());
                brochure.setTitle(t[0]);
                brochure.setTitleCkb(t[1]);
                brochure.setTitleKmr(t[2]);
                if (dto.getFileType() != null) {
                    brochure.setFileType(dto.getFileType());
                } else if (isNew) {
                    brochure.setFileType(guessBrochureType(
                            hasFile(upload) ? upload.getContentType()
                                            : (dto != null ? dto.getMimeType() : null)));
                }
                if (!hasFile(upload) && (isNew || dto.getSizeBytes() != 0)) {
                    brochure.setSizeBytes(dto.getSizeBytes());
                }
                if (!hasFile(upload) && dto.getMimeType() != null) {
                    brochure.setMimeType(trimOrNull(dto.getMimeType()));
                }
            }
            brochure.setBrochureOrder(dto != null && dto.getBrochureOrder() != null
                    ? dto.getBrochureOrder() : i);
            merged.add(brochure);
        }
        return merged;
    }

    private SoundAlbumBrochure resolveExistingAlbumBrochure(
            Map<Long, SoundAlbumBrochure> existingById,
            Map<String, SoundAlbumBrochure> existingBySource,
            Set<Long> requestedIds,
            AlbumBrochureRequest dto
    ) {
        if (dto == null) return null;
        if (dto.getId() != null) {
            SoundAlbumBrochure existing = existingById.get(dto.getId());
            if (existing != null && requestedIds.add(dto.getId())) {
                return existing;
            }
        }
        SoundAlbumBrochure bySource = firstByUrls(existingBySource, dto.getFileUrl());
        if (bySource != null && bySource.getId() != null
                && requestedIds.add(bySource.getId())) {
            return bySource;
        }
        return null;
    }

    private AttachmentType guessBrochureType(String mimeType) {
        if (mimeType == null) return AttachmentType.OTHER;
        String m = mimeType.toLowerCase();
        if (m.startsWith("image/")) return AttachmentType.IMAGE;
        if (m.equals("application/pdf")) return AttachmentType.PDF;
        if (m.startsWith("video/")) return AttachmentType.VIDEO;
        if (m.startsWith("audio/")) return AttachmentType.AUDIO;
        return AttachmentType.OTHER;
    }

    // =========================================================================
    // TOPIC HELPERS
    // =========================================================================

    private PublishmentTopic resolveOrCreateTopic(Long topicId, InlineTopicRequest newTopic) {
        if (topicId != null) return findSoundTopicOrThrow(topicId);
        if (newTopic != null) return createInlineTopic(newTopic);
        return null;
    }

    /**
     * Resolves every supplied topic reference — plural fields first, then the
     * legacy singular fields merged in — into an ordered de-duplicated set.
     * Unknown ids throw; ids pointing at a wrong-typed topic are ignored.
     */
    private Set<PublishmentTopic> resolveOrCreateTopics(
            Set<Long> topicIds,
            List<InlineTopicRequest> newTopics,
            Long legacyTopicId,
            InlineTopicRequest legacyNewTopic
    ) {
        Set<PublishmentTopic> out = new LinkedHashSet<>();
        if (topicIds != null) {
            topicIds.stream().filter(Objects::nonNull).distinct()
                    .forEach(id -> {
                        PublishmentTopic t = findSoundTopicOrThrow(id);
                        if (t != null) out.add(t);
                    });
        }
        if (legacyTopicId != null) {
            PublishmentTopic t = findSoundTopicOrThrow(legacyTopicId);
            if (t != null) out.add(t);
        }
        if (newTopics != null) {
            newTopics.stream().filter(Objects::nonNull)
                    .forEach(n -> {
                        PublishmentTopic t = createInlineTopic(n);
                        if (t != null) out.add(t);
                    });
        }
        if (legacyNewTopic != null) {
            PublishmentTopic t = createInlineTopic(legacyNewTopic);
            if (t != null) out.add(t);
        }
        return out;
    }

    private PublishmentTopic createInlineTopic(InlineTopicRequest newTopic) {
        if (isBlank(newTopic.getNameCkb()) && isBlank(newTopic.getNameKmr()))
            return null; // unnamed topic — link nothing instead of rejecting
        PublishmentTopic created = topicRepository.save(
                PublishmentTopic.builder()
                        .entityType(TOPIC_ENTITY_TYPE)
                        .nameCkb(trimOrNull(newTopic.getNameCkb()))
                        .nameKmr(trimOrNull(newTopic.getNameKmr()))
                        .build());
        log.info("بابەتی SOUND دروستکرا inline id={}", created.getId());
        return created;
    }

    /** Merges the plural soundTypes set with the legacy singular soundType. */
    private Set<String> mergedTypeSet(Set<String> soundTypes, String soundType) {
        Set<String> out = new LinkedHashSet<>(cleanStrings(soundTypes));
        if (!isBlank(soundType)) out.add(soundType.trim());
        return out;
    }

    private PublishmentTopic findSoundTopicOrThrow(Long topicId) {
        PublishmentTopic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> Errors.notFound("topic.not_found", Map.of("id", topicId)));
        if (!TOPIC_ENTITY_TYPE.equals(topic.getEntityType()))
            return null; // wrong-typed topic — ignore the link instead of rejecting
        return topic;
    }

    // =========================================================================
    // CONTENT HELPERS
    // =========================================================================

    private void applyContentByLanguages(
            SoundTrack entity, Set<Language> langs,
            LanguageContentDto ckb, LanguageContentDto kmr
    ) {
        Set<Language> safe = safeLangs(langs);
        entity.setCkbContent(safe.contains(Language.CKB) ? buildContent(ckb) : null);
        entity.setKmrContent(safe.contains(Language.KMR) ? buildContent(kmr) : null);
    }

    private SoundTrackContent buildContent(LanguageContentDto dto) {
        if (dto == null) return null;
        if (isBlank(dto.getTitle()) && isBlank(dto.getDescription())
               ) return null;
        return SoundTrackContent.builder()
                .title(trimOrNull(dto.getTitle()))
                .description(tiptapHtmlProcessor.process(trimOrNull(dto.getDescription()), "sounds"))
                .build();
    }

    private void applyContentForUpdate(
            SoundTrack entity,
            Set<Language> languages,
            LanguageContentDto ckb,
            LanguageContentDto kmr
    ) {
        Set<Language> safe = safeLangs(languages);
        if (safe.contains(Language.CKB)) {
            if (ckb != null) {
                entity.setCkbContent(mergeContent(entity.getCkbContent(), ckb));
            }
        } else {
            entity.setCkbContent(null);
        }

        if (safe.contains(Language.KMR)) {
            if (kmr != null) {
                entity.setKmrContent(mergeContent(entity.getKmrContent(), kmr));
            }
        } else {
            entity.setKmrContent(null);
        }
    }

    private SoundTrackContent mergeContent(
            SoundTrackContent existing,
            LanguageContentDto dto
    ) {
        if (existing == null) return buildContent(dto);
        if (dto.getTitle() != null) existing.setTitle(trimOrNull(dto.getTitle()));
        if (dto.getDescription() != null) {
            existing.setDescription(
                    tiptapHtmlProcessor.process(trimOrNull(dto.getDescription()), "sounds"));
        }
        return existing;
    }

    // =========================================================================
    // VALIDATION
    // =========================================================================

    /** All fields optional — missing values fall back to safe defaults. */
    private void validateCreate(CreateRequest dto) {
        if (dto == null) return;
        // Only default the legacy singular when the whole type set is empty —
        // otherwise "OTHER" would leak into a populated multi-select.
        if (isBlank(dto.getSoundType())
                && (dto.getSoundTypes() == null || dto.getSoundTypes().isEmpty()))
            dto.setSoundType("OTHER");
        if (dto.getTrackState() == null)   dto.setTrackState(TrackState.SINGLE);
    }

    // =========================================================================
    // ENTITY → DTO
    // =========================================================================

    private Response toResponse(SoundTrack s) {
        List<FileResponse> fileResponses = s.getFiles() == null ? List.of()
                : new ArrayList<>(s.getFiles()).stream()
                .map(this::toFileResponse).collect(Collectors.toList());

        long totalDuration = fileResponses.stream().mapToLong(FileResponse::getDurationSeconds).sum();
        long totalSize     = fileResponses.stream().mapToLong(FileResponse::getSizeBytes).sum();

        List<AttachmentResponse> attachResponses = s.getAttachments() == null ? List.of()
                : new ArrayList<>(s.getAttachments()).stream()
                .map(this::toAttachmentResponse).collect(Collectors.toList());

        List<AlbumBrochureResponse> brochureResponses = s.getBrochures() == null ? List.of()
                : new ArrayList<>(s.getBrochures()).stream()
                .map(this::toAlbumBrochureResponse).collect(Collectors.toList());

        List<TopicView> topicViews = s.getTopics() == null ? List.of()
                : s.getTopics().stream()
                .map(t -> TopicView.builder()
                        .id(t.getId())
                        .nameCkb(t.getNameCkb())
                        .nameKmr(t.getNameKmr())
                        .createdAt(t.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
        PublishmentTopic firstTopic = topicViews.isEmpty() ? null
                : s.getTopics().iterator().next();

        return Response.builder()
                .id(s.getId())
                .ckbCoverUrl(s.getCkbCoverUrl()).kmrCoverUrl(s.getKmrCoverUrl())
                .hoverCoverUrl(s.getHoverCoverUrl())
                .featureImageUrl(s.getFeatureImageUrl())
                .soundType(s.getSoundType()).trackState(s.getTrackState())
                .soundTypes(s.getSoundTypes() == null
                        ? new LinkedHashSet<>() : new LinkedHashSet<>(s.getSoundTypes()))
                .albumOfMemories(s.isAlbumOfMemories())
                .topicId(firstTopic != null ? firstTopic.getId() : null)
                .topicNameCkb(firstTopic != null ? firstTopic.getNameCkb() : null)
                .topicNameKmr(firstTopic != null ? firstTopic.getNameKmr() : null)
                .topics(topicViews)
                .contentLanguages(s.getContentLanguages() != null
                        ? new LinkedHashSet<>(s.getContentLanguages()) : new LinkedHashSet<>())
                .ckbContent(toContentDto(s.getCkbContent()))
                .kmrContent(toContentDto(s.getKmrContent()))
                .locations(new LinkedHashSet<>(safeSet(s.getLocations())))
                .reader(s.getReader())
                .directors(new LinkedHashSet<>(safeSet(s.getDirectors())))
                .terms(s.getTerms())
                .thisProjectOfInstitute(s.isThisProjectOfInstitute())
                .tags(BilingualSet.builder()
                        .ckb(new LinkedHashSet<>(safeSet(s.getTagsCkb())))
                        .kmr(new LinkedHashSet<>(safeSet(s.getTagsKmr()))).build())
                .keywords(BilingualSet.builder()
                        .ckb(new LinkedHashSet<>(safeSet(s.getKeywordsCkb())))
                        .kmr(new LinkedHashSet<>(safeSet(s.getKeywordsKmr()))).build())
                .files(fileResponses)
                .totalDurationSeconds(totalDuration).totalSizeBytes(totalSize)
                .albumName(s.getAlbumName()).publishmentYear(s.getPublishmentYear())
                .cdNumber(s.getCdNumber()).totalTracks(s.getTotalTracks())
                .attachments(attachResponses)
                .brochures(brochureResponses)
                .sortOrder(s.getSortOrder())
                .createdAt(s.getCreatedAt()).updatedAt(s.getUpdatedAt())
                .build();
    }

    private Pageable featuredPageable(int page, int size) {
        return PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.ASC, "featuredOrder")
                        .and(Sort.by(Sort.Direction.DESC, "id"))
        );
    }

    /**
     * Merge a bilingual file name. Explicit CKB/KMR values win; a bare legacy
     * value (older clients) fills CKB. Returns {legacyMirror, ckb, kmr} — the
     * legacy column always mirrors the primary (CKB, else KMR).
     */
    private static String[] mergeBilingual(String curLegacy, String curCkb, String curKmr,
                                           String inLegacy, String inCkb, String inKmr) {
        String ckb = curCkb;
        String kmr = curKmr;
        if (ckb == null && kmr == null) ckb = trimOrNull(curLegacy);
        if (inCkb != null) ckb = trimOrNull(inCkb);
        if (inKmr != null) kmr = trimOrNull(inKmr);
        if (inCkb == null && inKmr == null && inLegacy != null) ckb = trimOrNull(inLegacy);
        return new String[]{ckb != null ? ckb : kmr, ckb, kmr};
    }

    private static String firstNonBlankOf(String a, String b) {
        return a != null && !a.isBlank() ? a : (b != null && !b.isBlank() ? b : null);
    }

    private FileResponse toFileResponse(SoundTrackFile f) {
        List<BrochureResponse> brochures = f.getBrochures() == null ? List.of()
                : new ArrayList<>(f.getBrochures()).stream()
                .map(b -> BrochureResponse.builder()
                        .id(b.getId()).imageUrl(b.getImageUrl())
                        .caption(b.getCaption())
                        .captionCkb(firstNonBlankOf(b.getCaptionCkb(), b.getCaptionKmr() == null ? b.getCaption() : null))
                        .captionKmr(b.getCaptionKmr())
                        .brochureOrder(b.getBrochureOrder()).build())
                .collect(Collectors.toList());

        return FileResponse.builder()
                .id(f.getId()).fileUrl(f.getFileUrl())
                .externalUrl(f.getExternalUrl()).embedUrl(f.getEmbedUrl())
                .title(f.getTitle())
                .titleCkb(firstNonBlankOf(f.getTitleCkb(), f.getTitleKmr() == null ? f.getTitle() : null))
                .titleKmr(f.getTitleKmr())
                .fileType(f.getFileType())
                .publishmentYear(f.getPublishmentYear())
                .sizeBytes(f.getSizeBytes()).durationSeconds(f.getDurationSeconds())
                .durationMinutes(f.getDurationMinutes())
                .bitRate(f.getBitRate()).sampleRate(f.getSampleRate())
                .audioChannel(f.getAudioChannel()).form(f.getForm())
                .genre(f.getGenre()).recordingVenue(f.getRecordingVenue())
                .sortOrder(f.getSortOrder())
                .brochures(brochures)
                .build();
    }

    private AttachmentResponse toAttachmentResponse(SoundTrackAttachment a) {
        return AttachmentResponse.builder()
                .id(a.getId()).fileUrl(a.getFileUrl()).title(a.getTitle())
                .titleCkb(firstNonBlankOf(a.getTitleCkb(), a.getTitleKmr() == null ? a.getTitle() : null))
                .titleKmr(a.getTitleKmr())
                .attachmentType(a.getAttachmentType()).sizeBytes(a.getSizeBytes())
                .mimeType(a.getMimeType()).attachmentOrder(a.getAttachmentOrder())
                .build();
    }

    private AlbumBrochureResponse toAlbumBrochureResponse(SoundAlbumBrochure b) {
        return AlbumBrochureResponse.builder()
                .id(b.getId()).fileUrl(b.getFileUrl()).title(b.getTitle())
                .titleCkb(firstNonBlankOf(b.getTitleCkb(), b.getTitleKmr() == null ? b.getTitle() : null))
                .titleKmr(b.getTitleKmr())
                .fileType(b.getFileType()).sizeBytes(b.getSizeBytes())
                .mimeType(b.getMimeType()).brochureOrder(b.getBrochureOrder())
                .build();
    }

    private SoundReklamVideoResponse toSoundReklamVideoResponse(SoundReklamVideo reklamVideo) {
        return SoundReklamVideoResponse.builder()
                .id(reklamVideo.getId())
                .videoUrl(reklamVideo.getVideoUrl())
                .sizeBytes(reklamVideo.getSizeBytes())
                .mimeType(reklamVideo.getMimeType())
                .createdAt(reklamVideo.getCreatedAt())
                .updatedAt(reklamVideo.getUpdatedAt())
                .build();
    }

    private LanguageContentDto toContentDto(SoundTrackContent c) {
        if (c == null) return null;
        return LanguageContentDto.builder()
                .title(c.getTitle()).description(c.getDescription()).build();
    }

    // =========================================================================
    // LOGGING
    // =========================================================================

    private void createLog(Long id, String title, String action, String details) {
        try {
            soundTrackLogRepository.save(SoundTrackLog.builder()
                    .soundTrackRefId(id).soundTrackTitle(title)
                    .action(action).details(details).actorName("system").build());
        } catch (Exception e) {
            log.warn("شکستی تۆمارکردنی لۆگی سەدا | id={}", id, e);
        }
    }

    // =========================================================================
    // UTILITIES
    // =========================================================================

    private String titleOf(SoundTrack s) {
        if (s == null) return "";
        if (s.getCkbContent() != null && !isBlank(s.getCkbContent().getTitle()))
            return s.getCkbContent().getTitle();
        if (s.getKmrContent() != null && !isBlank(s.getKmrContent().getTitle()))
            return s.getKmrContent().getTitle();
        return "سەدا#" + s.getId();
    }

    

    private boolean hasFile(MultipartFile f)   { return f != null && !f.isEmpty(); }

    private boolean hasUploads(List<MultipartFile> files) {
        return nonEmptyFileCount(files) > 0;
    }

    private int nonEmptyFileCount(List<MultipartFile> files) {
        return files == null ? 0
                : (int) files.stream().filter(this::hasFile).count();
    }

    private void validatePromoVideoFile(MultipartFile videoFile) {
        if (!hasFile(videoFile)) {
            throw Errors.soundValidation("error.validation", Map.of(
                    "field", "videoFile",
                    "message", "ڤیدیۆ پێویستە"
            ));
        }
        String contentType = trimOrNull(videoFile.getContentType());
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("video/")) {
            throw Errors.soundValidation("error.validation", Map.of(
                    "field", "videoFile",
                    "message", "فایلی نێردراو دەبێت ڤیدیۆ بێت"
            ));
        }
    }

    private SoundReklamVideo findSoundReklamVideoOrThrow() {
        return soundReklamVideoRepository.findTopByOrderByIdAsc()
                .orElseThrow(() -> Errors.notFound(SOUND_REKLAM_VIDEO_NOT_FOUND, Map.of()));
    }

    private String uploadFile(MultipartFile f) throws IOException {
        return s3Service.upload(f.getBytes(), f.getOriginalFilename(), f.getContentType(),
                s3Service.entityFolder("sounds", f.getContentType()));
    }

    private String resolveCoverUrl(String dtoUrl, MultipartFile file) throws IOException {
        if (hasFile(file)) return uploadFile(file);
        return isBlank(dtoUrl) ? null : dtoUrl.trim();
    }

    private boolean isBlank(String s)   { return s == null || s.isBlank(); }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private Set<Language> safeLangs(Set<Language> in) {
        return in == null ? new LinkedHashSet<>() : new LinkedHashSet<>(in);
    }

    private Set<String> safeSet(Set<String> in) {
        return in == null ? new LinkedHashSet<>() : new LinkedHashSet<>(in);
    }

    private Set<String> cleanStrings(Set<String> in) {
        if (in == null) return new LinkedHashSet<>();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : in) { String t = trimOrNull(s); if (t != null) out.add(t); }
        return out;
    }

    private MultipartFile nextNonEmpty(List<MultipartFile> files, int start) {
        if (files == null) return null;
        for (int i = start; i < files.size(); i++)
            if (files.get(i) != null && !files.get(i).isEmpty()) return files.get(i);
        return null;
    }

    private int advanceIndex(List<MultipartFile> files, int start) {
        if (files == null) return start;
        for (int i = start; i < files.size(); i++)
            if (files.get(i) != null && !files.get(i).isEmpty()) return i + 1;
        return files.size();
    }
}
