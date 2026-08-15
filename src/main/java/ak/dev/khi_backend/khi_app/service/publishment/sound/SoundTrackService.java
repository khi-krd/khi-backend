package ak.dev.khi_backend.khi_app.service.publishment.sound;

import ak.dev.khi_backend.khi_app.dto.publishment.sound.SoundTrackDtos.*;
import ak.dev.khi_backend.khi_app.enums.Language;
import ak.dev.khi_backend.khi_app.enums.publishment.AttachmentType;
import ak.dev.khi_backend.khi_app.enums.publishment.TrackState;
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
    private final S3Service                  s3Service;
    private final TiptapHtmlProcessor        tiptapHtmlProcessor;

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
            List<MultipartFile>  attachmentFiles
    ) {
        validateCreate(dto);

        try {
            String ckbCoverUrl   = resolveCoverUrl(dto.getCkbCoverUrl(), ckbCoverImage);
            String kmrCoverUrl   = resolveCoverUrl(dto.getKmrCoverUrl(), kmrCoverImage);
            String hoverCoverUrl = resolveCoverUrl(dto.getHoverCoverUrl(), hoverCoverImage);

            PublishmentTopic topic = resolveOrCreateTopic(dto.getTopicId(), dto.getNewTopic());

            SoundTrack entity = SoundTrack.builder()
                    .ckbCoverUrl(ckbCoverUrl)
                    .kmrCoverUrl(kmrCoverUrl)
                    .hoverCoverUrl(hoverCoverUrl)
                    .soundType(dto.getSoundType().trim())
                    .trackState(dto.getTrackState())
                    .albumOfMemories(dto.getAlbumOfMemories() != null && dto.getAlbumOfMemories())
                    .topic(topic)
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

            SoundTrack saved = soundTrackRepository.save(entity);

            createLog(saved.getId(), titleOf(saved), "CREATED",
                    "سەدا دروستکرا — جۆر=" + saved.getSoundType()
                            + " دۆخ=" + saved.getTrackState()
                            + (topic != null ? " بابەتid=" + topic.getId() : ""));

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
            List<MultipartFile>  attachmentFiles
    ) {
        if (id == null)
            throw Errors.soundValidation("error.validation",
                    Map.of("field", "id", "message", "ئایدی پێویستە"));

        SoundTrack entity = soundTrackRepository.findByIdWithGraph(id)
                .orElseThrow(() -> Errors.soundNotFound(id));

        boolean updatesFiles = dto.getFiles() != null || hasUploads(audioFiles);
        if (updatesFiles) {
            validateFileUpdate(entity, dto.getFiles(), audioFiles);
        }
        boolean updatesAttachments = dto.getAttachments() != null || hasUploads(attachmentFiles);
        if (updatesAttachments) {
            validateAttachmentUpdate(entity, dto.getAttachments());
        }
        boolean updatesTopic = !dto.isClearTopic()
                && (dto.getTopicId() != null || dto.getNewTopic() != null);
        PublishmentTopic resolvedTopic = updatesTopic
                ? resolveOrCreateTopic(dto.getTopicId(), dto.getNewTopic())
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
            if (!isBlank(dto.getSoundType()))     entity.setSoundType(dto.getSoundType().trim());
            if (dto.getTrackState()      != null)  entity.setTrackState(dto.getTrackState());
            if (dto.getAlbumOfMemories() != null)  entity.setAlbumOfMemories(dto.getAlbumOfMemories());

            // ── Topic ─────────────────────────────────────────────────────
            if (dto.isClearTopic()) {
                entity.setTopic(null);
            } else if (updatesTopic) {
                entity.setTopic(resolvedTopic);
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

            SoundTrack saved = soundTrackRepository.save(entity);

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
                throw Errors.soundValidation("soundTrack.file.source.required", Map.of(
                        "index", i,
                        "message",
                        "هەر فایلێک پێویستی بە لانیکەم fileUrl، externalUrl، یان embedUrl هەیە"));

            SoundTrackFile file = SoundTrackFile.builder()
                    .fileUrl(fileUrl)
                    .externalUrl(externalUrl)
                    .embedUrl(embedUrl)
                    .title(fDto != null ? trimOrNull(fDto.getTitle())          : null)
                    .fileType(fDto != null ? fDto.getFileType()                : null)
                    .publishmentYear(fDto != null ? fDto.getPublishmentYear()  : null)
                    .sizeBytes(sizeBytes)
                    .durationSeconds(durationSec)
                    .bitRate(fDto != null ? trimOrNull(fDto.getBitRate())      : null)
                    .sampleRate(fDto != null ? trimOrNull(fDto.getSampleRate()): null)
                    .audioChannel(fDto != null ? fDto.getAudioChannel()        : null)
                    .form(fDto != null ? trimOrNull(fDto.getForm())            : null)
                    .genre(fDto != null ? trimOrNull(fDto.getGenre())          : null)
                    .recordingVenue(fDto != null ? trimOrNull(fDto.getRecordingVenue()) : null)
                    .build();

            owner.addFile(file);

            List<BrochureRequest> brochureDtos = (fDto != null && fDto.getBrochures() != null)
                    ? fDto.getBrochures() : Collections.emptyList();
            brochureIdx = buildAndAttachBrochures(file, brochureDtos, brochureFiles, brochureIdx);
        }
    }

    private void validateFileUpdate(
            SoundTrack owner,
            List<FileCreateRequest> fileDtos,
            List<MultipartFile> audioFiles
    ) {
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
        Set<Long> requestedIds = new HashSet<>();
        int dtoCount = fileDtos == null ? 0 : fileDtos.size();
        int fileCount = nonEmptyFileCount(audioFiles);
        int total = Math.max(dtoCount, fileCount);
        int audioIndex = 0;

        for (int i = 0; i < total; i++) {
            FileCreateRequest dto = fileDtos != null && i < fileDtos.size()
                    ? fileDtos.get(i)
                    : null;
            MultipartFile upload = nextNonEmpty(audioFiles, audioIndex);
            if (upload != null) audioIndex = advanceIndex(audioFiles, audioIndex);

            SoundTrackFile existing = resolveExistingFile(
                    existingById, requestedIds, dto, i);
            validateBrochureIds(existing, dto, i);
            if (!hasFile(upload) && !hasSoundSource(dto)
                    && (existing == null || !hasSoundSource(existing))) {
                throw Errors.soundValidation("soundTrack.file.source.required", Map.of(
                        "field", "files[" + i + "]",
                        "message",
                        "هەر فایلێک پێویستی بە لانیکەم fileUrl، externalUrl، یان embedUrl هەیە"));
            }
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
                    existingById, requestedIds, dto, i);
            boolean isNew = file == null;
            if (isNew) {
                file = new SoundTrackFile();
                file.setSoundTrack(owner);
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
        if (dto.getTitle() != null) file.setTitle(trimOrNull(dto.getTitle()));
        if (dto.getFileType() != null) file.setFileType(dto.getFileType());
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
        Set<Long> requestedIds = new HashSet<>();
        List<SoundTrackBrochure> merged = new ArrayList<>();

        for (int i = 0; i < dtos.size(); i++) {
            BrochureRequest dto = dtos.get(i);
            if (dto == null) continue;

            MultipartFile upload = nextNonEmpty(uploads, uploadIndex[0]);
            if (upload != null) uploadIndex[0] = advanceIndex(uploads, uploadIndex[0]);

            SoundTrackBrochure brochure = resolveExistingBrochure(
                    existingById, requestedIds, dto, i);
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
            if (dto.getCaption() != null) {
                brochure.setCaption(trimOrNull(dto.getCaption()));
            }
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
                    existingById, requestedIds, dto, i);
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
                if (dto.getTitle() != null) attachment.setTitle(trimOrNull(dto.getTitle()));
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
            attachment.setAttachmentOrder(i);
            merged.add(attachment);
        }
        return merged;
    }

    private void validateBrochureIds(
            SoundTrackFile existingFile,
            FileCreateRequest fileDto,
            int fileIndex
    ) {
        if (fileDto == null || fileDto.getBrochures() == null) return;

        Map<Long, SoundTrackBrochure> existingById = existingFile == null
                ? Map.of()
                : existingFile.getBrochures().stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getId() != null)
                .collect(Collectors.toMap(
                        SoundTrackBrochure::getId,
                        item -> item,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
        Set<Long> requestedIds = new HashSet<>();

        for (int i = 0; i < fileDto.getBrochures().size(); i++) {
            BrochureRequest brochure = fileDto.getBrochures().get(i);
            if (brochure == null || brochure.getId() == null) continue;
            if (!existingById.containsKey(brochure.getId())
                    || !requestedIds.add(brochure.getId())) {
                throw Errors.soundValidation("error.validation", Map.of(
                        "field", "files[" + fileIndex + "].brochures[" + i + "].id",
                        "id", brochure.getId(),
                        "message", "ئایدی بڕۆشور لەم فایلەدا نەدۆزرایەوە یان دووبارەیە"));
            }
        }
    }

    private void validateAttachmentUpdate(
            SoundTrack owner,
            List<AttachmentRequest> dtos
    ) {
        if (dtos == null) return;

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
        Set<Long> requestedIds = new HashSet<>();

        for (int i = 0; i < dtos.size(); i++) {
            AttachmentRequest dto = dtos.get(i);
            if (dto == null || dto.getId() == null) continue;
            if (!existingById.containsKey(dto.getId())
                    || !requestedIds.add(dto.getId())) {
                throw invalidNestedId("attachments", i, dto.getId());
            }
        }
    }

    private SoundTrackFile resolveExistingFile(
            Map<Long, SoundTrackFile> existingById,
            Set<Long> requestedIds,
            FileCreateRequest dto,
            int index
    ) {
        if (dto == null || dto.getId() == null) return null;
        SoundTrackFile existing = existingById.get(dto.getId());
        if (existing == null || !requestedIds.add(dto.getId())) {
            throw invalidNestedId("files", index, dto.getId());
        }
        return existing;
    }

    private SoundTrackBrochure resolveExistingBrochure(
            Map<Long, SoundTrackBrochure> existingById,
            Set<Long> requestedIds,
            BrochureRequest dto,
            int index
    ) {
        if (dto.getId() == null) return null;
        SoundTrackBrochure existing = existingById.get(dto.getId());
        if (existing == null || !requestedIds.add(dto.getId())) {
            throw invalidNestedId("brochures", index, dto.getId());
        }
        return existing;
    }

    private SoundTrackAttachment resolveExistingAttachment(
            Map<Long, SoundTrackAttachment> existingById,
            Set<Long> requestedIds,
            AttachmentRequest dto,
            int index
    ) {
        if (dto == null || dto.getId() == null) return null;
        SoundTrackAttachment existing = existingById.get(dto.getId());
        if (existing == null || !requestedIds.add(dto.getId())) {
            throw invalidNestedId("attachments", index, dto.getId());
        }
        return existing;
    }

    private RuntimeException invalidNestedId(
            String field,
            int index,
            Long id
    ) {
        return Errors.soundValidation("error.validation", Map.of(
                "field", field + "[" + index + "].id",
                "id", id,
                "message", "ئایدی فایل لەم سەدايەدا نەدۆزرایەوە یان دووبارەیە"));
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

            file.addBrochure(SoundTrackBrochure.builder()
                    .imageUrl(imageUrl)
                    .caption(trimOrNull(bDto.getCaption()))
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

            owner.addAttachment(SoundTrackAttachment.builder()
                    .fileUrl(fileUrl)
                    .title(aDto != null ? trimOrNull(aDto.getTitle()) : null)
                    .attachmentType(resolvedType)
                    .sizeBytes(fileSize)
                    .mimeType(aDto != null ? trimOrNull(aDto.getMimeType()) : null)
                    .attachmentOrder(i)
                    .build());
        }
    }

    // =========================================================================
    // TOPIC HELPERS
    // =========================================================================

    private PublishmentTopic resolveOrCreateTopic(Long topicId, InlineTopicRequest newTopic) {
        if (topicId != null) return findSoundTopicOrThrow(topicId);
        if (newTopic != null) {
            if (isBlank(newTopic.getNameCkb()) && isBlank(newTopic.getNameKmr()))
                throw Errors.soundValidation("error.validation", Map.of(
                        "message",
                        "بابەتی نوێ پێویستی بە لانیکەم ناوێکی کوردییە (ناوەندی یان باکوور)"));
            PublishmentTopic created = topicRepository.save(
                    PublishmentTopic.builder()
                            .entityType(TOPIC_ENTITY_TYPE)
                            .nameCkb(trimOrNull(newTopic.getNameCkb()))
                            .nameKmr(trimOrNull(newTopic.getNameKmr()))
                            .build());
            log.info("بابەتی SOUND دروستکرا inline id={}", created.getId());
            return created;
        }
        return null;
    }

    private PublishmentTopic findSoundTopicOrThrow(Long topicId) {
        PublishmentTopic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> Errors.notFound("topic.not_found", Map.of("id", topicId)));
        if (!TOPIC_ENTITY_TYPE.equals(topic.getEntityType()))
            throw Errors.soundValidation("topic.type.mismatch", Map.of(
                    "message", "بابەت id=" + topicId + " بۆ '"
                            + topic.getEntityType() + "'ە، چاوەڕوان دەکرێت 'SOUND' بێت"));
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
                .description(tiptapHtmlProcessor.process(trimOrNull(dto.getDescription())))
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
                    tiptapHtmlProcessor.process(trimOrNull(dto.getDescription())));
        }
        return existing;
    }

    // =========================================================================
    // VALIDATION
    // =========================================================================

    private void validateCreate(CreateRequest dto) {
        if (dto == null)
            throw Errors.soundValidation("error.validation", Map.of("field", "data"));
        if (isBlank(dto.getSoundType()))
            throw Errors.soundValidation("error.validation",
                    Map.of("field", "soundType", "message", "جۆری دەنگ پێویستە"));
        if (dto.getTrackState() == null)
            throw Errors.soundValidation("error.validation",
                    Map.of("field", "trackState", "message", "دۆخی تراک پێویستە"));
        if (safeLangs(dto.getContentLanguages()).isEmpty())
            throw Errors.soundValidation("soundTrack.languages.required",
                    Map.of("field", "contentLanguages"));
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

        return Response.builder()
                .id(s.getId())
                .ckbCoverUrl(s.getCkbCoverUrl()).kmrCoverUrl(s.getKmrCoverUrl())
                .hoverCoverUrl(s.getHoverCoverUrl())
                .featureImageUrl(s.getFeatureImageUrl())
                .soundType(s.getSoundType()).trackState(s.getTrackState())
                .albumOfMemories(s.isAlbumOfMemories())
                .topicId(s.getTopic()      != null ? s.getTopic().getId()      : null)
                .topicNameCkb(s.getTopic() != null ? s.getTopic().getNameCkb() : null)
                .topicNameKmr(s.getTopic() != null ? s.getTopic().getNameKmr() : null)
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

    private FileResponse toFileResponse(SoundTrackFile f) {
        List<BrochureResponse> brochures = f.getBrochures() == null ? List.of()
                : new ArrayList<>(f.getBrochures()).stream()
                .map(b -> BrochureResponse.builder()
                        .id(b.getId()).imageUrl(b.getImageUrl())
                        .caption(b.getCaption()).brochureOrder(b.getBrochureOrder()).build())
                .collect(Collectors.toList());

        return FileResponse.builder()
                .id(f.getId()).fileUrl(f.getFileUrl())
                .externalUrl(f.getExternalUrl()).embedUrl(f.getEmbedUrl())
                .title(f.getTitle()).fileType(f.getFileType())
                .publishmentYear(f.getPublishmentYear())
                .sizeBytes(f.getSizeBytes()).durationSeconds(f.getDurationSeconds())
                .durationMinutes(f.getDurationMinutes())
                .bitRate(f.getBitRate()).sampleRate(f.getSampleRate())
                .audioChannel(f.getAudioChannel()).form(f.getForm())
                .genre(f.getGenre()).recordingVenue(f.getRecordingVenue())
                .brochures(brochures)
                .build();
    }

    private AttachmentResponse toAttachmentResponse(SoundTrackAttachment a) {
        return AttachmentResponse.builder()
                .id(a.getId()).fileUrl(a.getFileUrl()).title(a.getTitle())
                .attachmentType(a.getAttachmentType()).sizeBytes(a.getSizeBytes())
                .mimeType(a.getMimeType()).attachmentOrder(a.getAttachmentOrder())
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
        return s3Service.upload(f.getBytes(), f.getOriginalFilename(), f.getContentType());
    }

    private String resolveCoverUrl(String dtoUrl, MultipartFile file) throws IOException {
        if (hasFile(file)) return uploadFile(file);
        return isBlank(dtoUrl) ? null : dtoUrl.trim();
    }

    private boolean isBlank(String s)   { return s == null || s.isBlank(); }

    private String trimOrNull(String s) {
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
