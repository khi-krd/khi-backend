package ak.dev.khi_backend.khi_app.service.publishment.image;

import ak.dev.khi_backend.khi_app.dto.publishment.image.ImageCollectionDTO.*;
import ak.dev.khi_backend.khi_app.enums.Language;
import ak.dev.khi_backend.khi_app.enums.publishment.ImageCollectionType;
import ak.dev.khi_backend.khi_app.exceptions.NotFoundException;
import ak.dev.khi_backend.khi_app.exceptions.Errors;
import ak.dev.khi_backend.khi_app.model.publishment.image.*;
import ak.dev.khi_backend.khi_app.model.publishment.topic.PublishmentTopic;
import ak.dev.khi_backend.khi_app.repository.publishment.image.ImageCollectionLogRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.image.ImageCollectionRepository;
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

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageCollectionService {

    private static final String TOPIC_ENTITY_TYPE = "IMAGE";

    private final ImageCollectionRepository    imageCollectionRepository;
    private final ImageCollectionLogRepository imageCollectionLogRepository;
    private final PublishmentTopicRepository   topicRepository;
    private final S3Service                    s3Service;
    private final TiptapHtmlProcessor          tiptapHtmlProcessor;

    // =========================================================================
    // دروستکردن (CREATE)
    // =========================================================================

    @CacheEvict(value = "imageCollections", allEntries = true)
    @Transactional
    public Response create(
            CreateRequest dto,
            MultipartFile ckbCoverImage,
            MultipartFile kmrCoverImage,
            MultipartFile hoverCoverImage,
            List<MultipartFile> images
    ) {
        validateCreate(dto, ckbCoverImage);

        try {
            String ckbCoverUrl   = resolveCoverUrl(dto.getCkbCoverUrl(),   ckbCoverImage);
            String kmrCoverUrl   = resolveCoverUrl(dto.getKmrCoverUrl(),   kmrCoverImage);
            String hoverCoverUrl = resolveCoverUrl(dto.getHoverCoverUrl(), hoverCoverImage);

            PublishmentTopic topic = resolveOrCreateTopic(dto.getTopicId(), dto.getNewTopic());

            ImageCollection entity = ImageCollection.builder()
                    .slugCkb(trimOrNull(dto.getSlugCkb()))
                    .slugKmr(trimOrNull(dto.getSlugKmr()))
                    .collectionType(dto.getCollectionType())
                    .ckbCoverUrl(ckbCoverUrl)
                    .kmrCoverUrl(kmrCoverUrl)
                    .hoverCoverUrl(hoverCoverUrl)
                    .topic(topic)
                    .publishmentDate(dto.getPublishmentDate())
                    .contentLanguages(new LinkedHashSet<>(safeLangs(dto.getContentLanguages())))
                    .tagsCkb(new LinkedHashSet<>(safeSet(
                            dto.getTags() != null ? dto.getTags().getCkb() : null)))
                    .tagsKmr(new LinkedHashSet<>(safeSet(
                            dto.getTags() != null ? dto.getTags().getKmr() : null)))
                    .keywordsCkb(new LinkedHashSet<>(safeSet(
                            dto.getKeywords() != null ? dto.getKeywords().getCkb() : null)))
                    .keywordsKmr(new LinkedHashSet<>(safeSet(
                            dto.getKeywords() != null ? dto.getKeywords().getKmr() : null)))
                    .build();

            applyContentByLanguages(entity,
                    dto.getContentLanguages(), dto.getCkbContent(), dto.getKmrContent());

            List<ImageAlbumItem> items = buildAlbumItems(
                    entity, dto.getCollectionType(), dto.getImageAlbum(), images);
            entity.getImageAlbum().addAll(items);

            ImageCollection saved = imageCollectionRepository.save(entity);
            createLog(saved.getId(), titleOf(saved), "CREATE",
                    "کۆمەڵەی وێنە دروستکرا — جۆر=" + saved.getCollectionType()
                            + (topic != null ? " بابەتid=" + topic.getId() : ""));

            return toResponse(saved);

        } catch (IOException e) {
            throw Errors.imageStorageFailed("image.media_upload_failed",
                    Map.of("reason", "کێشە لە ناردنی وێنە: " + e.getMessage()), e);
        }
    }

    // =========================================================================
    // نوێکردنەوە (UPDATE)
    // =========================================================================

    @CacheEvict(value = "imageCollections", allEntries = true)
    @Transactional
    public Response update(
            Long id,
            UpdateRequest dto,
            MultipartFile ckbCoverImage,
            MultipartFile kmrCoverImage,
            MultipartFile hoverCoverImage,
            List<MultipartFile> images
    ) {
        if (id == null) {
            throw Errors.imageValidation("error.validation",
                    Map.of("field", "id", "message", "ئایدی پێویستە"));
        }

        ImageCollection entity = imageCollectionRepository.findByIdWithGraph(id)
                .orElseThrow(() -> Errors.imageNotFound(id));

        boolean hasUploads = hasUploads(images);
        boolean updatesAlbum = dto.getImageAlbum() != null || hasUploads;
        ImageCollectionType targetType = dto.getCollectionType() != null
                ? dto.getCollectionType()
                : entity.getCollectionType();

        // Validate the complete album plan before processing Tiptap content or
        // uploading any cover/album files. S3 writes cannot be rolled back with
        // the database transaction.
        if (updatesAlbum) {
            validateAlbumUpdate(entity, targetType, dto.getImageAlbum(), images);
        } else if (dto.getCollectionType() != null) {
            validateAlbumItemCount(targetType, entity.getImageAlbum().size());
        }

        boolean updatesTopic = !dto.isClearTopic()
                && (dto.getTopicId() != null || dto.getNewTopic() != null);
        PublishmentTopic resolvedTopic = updatesTopic
                ? resolveOrCreateTopic(dto.getTopicId(), dto.getNewTopic())
                : null;

        try {
            if (dto.getSlugCkb() != null) entity.setSlugCkb(trimOrNull(dto.getSlugCkb()));
            if (dto.getSlugKmr() != null) entity.setSlugKmr(trimOrNull(dto.getSlugKmr()));
            if (dto.getCollectionType() != null) {
                entity.setCollectionType(dto.getCollectionType());
            }

            if (hasFile(ckbCoverImage)) {
                entity.setCkbCoverUrl(uploadFile(ckbCoverImage));
            } else if (!isBlank(dto.getCkbCoverUrl())) {
                entity.setCkbCoverUrl(dto.getCkbCoverUrl().trim());
            }

            if (hasFile(kmrCoverImage)) {
                entity.setKmrCoverUrl(uploadFile(kmrCoverImage));
            } else if (!isBlank(dto.getKmrCoverUrl())) {
                entity.setKmrCoverUrl(dto.getKmrCoverUrl().trim());
            }

            if (hasFile(hoverCoverImage)) {
                entity.setHoverCoverUrl(uploadFile(hoverCoverImage));
            } else if (!isBlank(dto.getHoverCoverUrl())) {
                entity.setHoverCoverUrl(dto.getHoverCoverUrl().trim());
            }

            if (dto.isClearTopic()) {
                entity.setTopic(null);
            } else if (updatesTopic) {
                entity.setTopic(resolvedTopic);
            }

            if (dto.getPublishmentDate() != null) {
                entity.setPublishmentDate(dto.getPublishmentDate());
            }

            if (dto.getContentLanguages() != null) {
                entity.getContentLanguages().clear();
                entity.getContentLanguages().addAll(safeLangs(dto.getContentLanguages()));
            }

            applyContentForUpdate(entity, entity.getContentLanguages(),
                    dto.getCkbContent(), dto.getKmrContent());

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

            if (updatesAlbum) {
                List<ImageAlbumItem> mergedItems = mergeAlbumItems(
                        entity, targetType, dto.getImageAlbum(), images);
                entity.getImageAlbum().clear();
                entity.getImageAlbum().addAll(mergedItems);
            }

            ImageCollection saved = imageCollectionRepository.save(entity);
            createLog(saved.getId(), titleOf(saved), "UPDATE",
                    "کۆمەڵەی وێنە نوێکرایەوە — جۆر=" + saved.getCollectionType());

            return toResponse(saved);

        } catch (IOException e) {
            throw Errors.imageStorageFailed("image.media_upload_failed",
                    Map.of("reason", "کێشە لە ناردنی وێنە: " + e.getMessage()), e);
        }
    }

    // =========================================================================
    // GET ALL — Paginated + Cached + Two-Phase @BatchSize
    // =========================================================================

    @Cacheable(value = "imageCollections", key = "'all:p' + #page + ':s' + #size")
    @Transactional(readOnly = true)
    public Page<Response> getAll(int page, int size) {
        Page<Long> idPage = imageCollectionRepository.findAllIds(PageRequest.of(page, size));

        if (idPage.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(),
                    idPage.getPageable(), idPage.getTotalElements());
        }

        return new PageImpl<>(
                hydrateAndSort(idPage.getContent()).stream()
                        .map(this::toResponse).collect(Collectors.toList()),
                idPage.getPageable(),
                idPage.getTotalElements()
        );
    }

    @Transactional(readOnly = true)
    public Page<Response> getFeatured(int page, int size) {
        Pageable pageable = featuredPageable(page, size);
        return imageCollectionRepository.findByFeaturedTrue(pageable).map(this::toResponse);
    }

    // =========================================================================
    // FILTER BY TYPE — Paginated + Cached
    // =========================================================================

    @Cacheable(value = "imageCollections",
            key = "'type:' + #type.name() + ':p' + #page + ':s' + #size")
    @Transactional(readOnly = true)
    public Page<Response> getByType(ImageCollectionType type, int page, int size) {
        if (type == null) {
            throw Errors.imageValidation("imageCollection.type.required",
                    Map.of("field", "type"));
        }

        Page<Long> idPage = imageCollectionRepository.findIdsByType(
                type, PageRequest.of(page, size));

        if (idPage.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(),
                    idPage.getPageable(), idPage.getTotalElements());
        }

        return new PageImpl<>(
                hydrateAndSort(idPage.getContent()).stream()
                        .map(this::toResponse).collect(Collectors.toList()),
                idPage.getPageable(),
                idPage.getTotalElements()
        );
    }

    // =========================================================================
    // SEARCH BY TAG — Paginated + Cached
    // =========================================================================

    @Cacheable(value = "imageCollections",
            key = "'tag:' + #tag.toLowerCase() + ':p' + #page + ':s' + #size")
    @Transactional(readOnly = true)
    public Page<Response> searchByTag(String tag, int page, int size) {
        if (isBlank(tag)) {
            throw Errors.badRequest("tag.required", Map.of("field", "tag"));
        }

        Page<Long> idPage = imageCollectionRepository.findIdsByTag(
                tag.trim(), PageRequest.of(page, size));

        if (idPage.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(),
                    idPage.getPageable(), idPage.getTotalElements());
        }

        return new PageImpl<>(
                hydrateAndSort(idPage.getContent()).stream()
                        .map(this::toResponse).collect(Collectors.toList()),
                idPage.getPageable(),
                idPage.getTotalElements()
        );
    }

    // =========================================================================
    // SEARCH BY KEYWORD — Paginated + Cached
    // =========================================================================

    @Cacheable(value = "imageCollections",
            key = "'kw:' + #keyword.toLowerCase() + ':p' + #page + ':s' + #size")
    @Transactional(readOnly = true)
    public Page<Response> searchByKeyword(String keyword, int page, int size) {
        if (isBlank(keyword)) {
            throw Errors.badRequest("keyword.required", Map.of("field", "keyword"));
        }

        Page<Long> idPage = imageCollectionRepository.findIdsByKeyword(
                keyword.trim(), PageRequest.of(page, size));

        if (idPage.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(),
                    idPage.getPageable(), idPage.getTotalElements());
        }

        return new PageImpl<>(
                hydrateAndSort(idPage.getContent()).stream()
                        .map(this::toResponse).collect(Collectors.toList()),
                idPage.getPageable(),
                idPage.getTotalElements()
        );
    }

    // =========================================================================
    // GLOBAL SEARCH — Paginated + Cached
    // =========================================================================

    @Cacheable(value = "imageCollections",
            key = "'search:' + #q.toLowerCase() + ':p' + #page + ':s' + #size")
    @Transactional(readOnly = true)
    public Page<Response> globalSearch(String q, int page, int size) {
        if (isBlank(q)) {
            throw Errors.badRequest("keyword.required", Map.of("field", "q"));
        }

        Page<Long> idPage = imageCollectionRepository.findIdsByGlobalSearch(
                q.trim(), PageRequest.of(page, size));

        if (idPage.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(),
                    idPage.getPageable(), idPage.getTotalElements());
        }

        return new PageImpl<>(
                hydrateAndSort(idPage.getContent()).stream()
                        .map(this::toResponse).collect(Collectors.toList()),
                idPage.getPageable(),
                idPage.getTotalElements()
        );
    }

    // =========================================================================
    // SEARCH BY TOPIC — Paginated + Cached
    // =========================================================================

    @Cacheable(value = "imageCollections",
            key = "'topic:' + #topicId + ':p' + #page + ':s' + #size")
    @Transactional(readOnly = true)
    public Page<Response> getByTopic(Long topicId, int page, int size) {
        if (topicId == null) {
            throw Errors.imageValidation("error.validation", Map.of("field", "topicId"));
        }

        Page<Long> idPage = imageCollectionRepository.findIdsByTopic(
                topicId, PageRequest.of(page, size));

        if (idPage.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(),
                    idPage.getPageable(), idPage.getTotalElements());
        }

        return new PageImpl<>(
                hydrateAndSort(idPage.getContent()).stream()
                        .map(this::toResponse).collect(Collectors.toList()),
                idPage.getPageable(),
                idPage.getTotalElements()
        );
    }

    // =========================================================================
    // GET BY ID
    // =========================================================================

    @Transactional(readOnly = true)
    public Response getById(Long id) {
        ImageCollection entity = imageCollectionRepository.findByIdWithGraph(id)
                .orElseThrow(() -> Errors.imageNotFound(id));
        return toResponse(entity);
    }

    @Transactional(readOnly = true)
    public Response getBySlug(String slug) {
        ImageCollection entity = imageCollectionRepository.findBySlugCkbOrSlugKmr(slug, slug)
                .orElseThrow(() -> Errors.notFound(
                        "imageCollection.not_found", Map.of("slug", slug)));
        return toResponse(entity);
    }

    // =========================================================================
    // سڕینەوە (DELETE)
    // =========================================================================

    @CacheEvict(value = "imageCollections", allEntries = true)
    @Transactional
    public void delete(Long id) {
        if (id == null) return;

        ImageCollection entity = imageCollectionRepository.findByIdWithGraph(id).orElse(null);
        if (entity == null) {
            log.debug("Image collection delete ignored; id={} does not exist", id);
            return;
        }
        createLog(entity.getId(), titleOf(entity), "DELETE",
                "کۆمەڵەی وێنە سڕایەوە — جۆر=" + entity.getCollectionType());
        imageCollectionRepository.delete(entity);
    }

    // =========================================================================
    // CORE HYDRATION HELPER
    // =========================================================================

    private List<ImageCollection> hydrateAndSort(List<Long> ids) {
        List<ImageCollection> rows = imageCollectionRepository.findAllByIds(ids);

        Map<Long, ImageCollection> indexed = new LinkedHashMap<>(rows.size());
        for (ImageCollection ic : rows) indexed.put(ic.getId(), ic);

        List<ImageCollection> ordered = new ArrayList<>(ids.size());
        for (Long id : ids) {
            ImageCollection ic = indexed.get(id);
            if (ic != null) ordered.add(ic);
        }
        return ordered;
    }

    // =========================================================================
    // یاریدەدەرەکانی بابەت (Topic Helpers)
    // =========================================================================

    private PublishmentTopic resolveOrCreateTopic(Long topicId, InlineTopicRequest newTopic) {
        if (topicId != null) {
            return findImageTopicOrThrow(topicId);
        }
        if (newTopic != null) {
            if (isBlank(newTopic.getNameCkb()) && isBlank(newTopic.getNameKmr())) {
                throw Errors.imageValidation("error.validation", Map.of(
                        "message",
                        "بابەتی نوێ پێویستی بە لانیکەم ناوێکی کوردییە (ناوەندی یان باکوور)"));
            }
            PublishmentTopic created = topicRepository.save(
                    PublishmentTopic.builder()
                            .entityType(TOPIC_ENTITY_TYPE)
                            .nameCkb(trimOrNull(newTopic.getNameCkb()))
                            .nameKmr(trimOrNull(newTopic.getNameKmr()))
                            .build());
            log.info("بابەتی IMAGE دروستکرا inline id={}", created.getId());
            return created;
        }
        return null;
    }

    private PublishmentTopic findImageTopicOrThrow(Long topicId) {
        PublishmentTopic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> Errors.notFound("topic.not_found", Map.of("id", topicId)));
            if (!TOPIC_ENTITY_TYPE.equals(topic.getEntityType())) {
            throw Errors.imageValidation("topic.type.mismatch", Map.of(
                    "message", "بابەت id=" + topicId
                            + " بۆ '" + topic.getEntityType()
                            + "'ە، چاوەڕوان دەکرێت 'IMAGE' بێت"));
        }
        return topic;
    }

    // =========================================================================
    // یاریدەدەرەکانی وێنەی بەرگ (Cover Image Helpers)
    // =========================================================================

    private String resolveCoverUrl(String dtoUrl, MultipartFile file) throws IOException {
        if (hasFile(file)) return uploadFile(file);
        return isBlank(dtoUrl) ? null : dtoUrl.trim();
    }

    private boolean hasFile(MultipartFile f) { return f != null && !f.isEmpty(); }

    private String uploadFile(MultipartFile f) throws IOException {
        return s3Service.upload(f.getBytes(), f.getOriginalFilename(), f.getContentType());
    }

    // =========================================================================
    // METADATA EXTRACTION (NEW - Auto extract width/height/size)
    // =========================================================================

    /**
     * Automatically extracts image metadata (dimensions, file size, mime type).
     * Reads bytes once and extracts metadata before upload.
     * Gracefully handles errors - upload continues even if extraction fails.
     */
    private void extractAndSetImageMetadata(ImageAlbumItem item, MultipartFile file, byte[] fileBytes) {
        try {
            // 1. Basic metadata from MultipartFile (always available)
            item.setFileSizeBytes(file.getSize());
            item.setMimeType(file.getContentType());

            // 2. Image dimensions using ImageIO
            try (InputStream is = new ByteArrayInputStream(fileBytes)) {
                BufferedImage img = ImageIO.read(is);
                if (img != null) {
                    item.setWidthPx(img.getWidth());
                    item.setHeightPx(img.getHeight());

                    log.debug("Metadata extracted: {}x{} ({} bytes) - {}",
                            item.getWidthPx(), item.getHeightPx(),
                            item.getFileSizeBytes(), file.getOriginalFilename());
                } else {
                    log.warn("Could not read image dimensions for {} - unsupported format or corrupted",
                            file.getOriginalFilename());
                }
            }
        } catch (Exception e) {
            // Log warning but don't fail - upload should continue even if metadata extraction fails
            log.warn("Failed to extract metadata for {}: {}",
                    file.getOriginalFilename(), e.getMessage());

            // At least try to set file size if available
            if (item.getFileSizeBytes() == null && file != null) {
                item.setFileSizeBytes(file.getSize());
            }
        }
    }

    // =========================================================================
    // دروستکردنی مادەکانی ئەلبوم (Album Item Builders)
    // =========================================================================

    private List<ImageAlbumItem> buildAlbumItems(
            ImageCollection owner,
            ImageCollectionType type,
            List<ImageItemDto> dtos,
            List<MultipartFile> files
    ) throws IOException {

        int fileCount = files == null ? 0
                : (int) files.stream().filter(f -> f != null && !f.isEmpty()).count();
        int dtoCount  = dtos == null ? 0 : dtos.size();
        int max       = Math.max(fileCount, dtoCount);

        validateAlbumItemCount(type, max);

        List<ImageAlbumItem> out = new ArrayList<>(max);
        int fileIndex = 0;

        for (int i = 0; i < max; i++) {
            MultipartFile file = nextNonEmpty(files, fileIndex);
            if (file != null) fileIndex = advanceFileIndex(files, fileIndex);

            ImageItemDto dto = (dtos != null && i < dtos.size()) ? dtos.get(i) : null;

            ImageAlbumItem item = new ImageAlbumItem();
            item.setImageCollection(owner);
            item.setSortOrder(dto != null && dto.getSortOrder() != null ? dto.getSortOrder() : i);

            if (dto != null) {
                item.setCaptionCkb(trimOrNull(dto.getCaptionCkb()));
                item.setCaptionKmr(trimOrNull(dto.getCaptionKmr()));
                item.setDescriptionCkb(tiptapHtmlProcessor.process(trimOrNull(dto.getDescriptionCkb())));
                item.setDescriptionKmr(tiptapHtmlProcessor.process(trimOrNull(dto.getDescriptionKmr())));
            }

            applyImageSource(item, file, dto);
            out.add(item);
        }
        return out;
    }

    /**
     * Validates an update album without uploading anything.
     *
     * Existing item IDs must belong to this collection. Existing items may omit
     * all source fields and retain their persisted source. New items still
     * require either a multipart file or a URL source.
     */
    private void validateAlbumUpdate(
            ImageCollection owner,
            ImageCollectionType type,
            List<ImageItemDto> dtos,
            List<MultipartFile> files
    ) {
        Map<Long, ImageAlbumItem> existingById = albumItemsById(owner);
        Set<Long> requestedIds = new HashSet<>();

        int fileCount = nonEmptyFileCount(files);
        int dtoCount = dtos == null ? 0 : dtos.size();
        int max = Math.max(fileCount, dtoCount);

        validateAlbumItemCount(type, max);

        int fileIndex = 0;
        for (int i = 0; i < max; i++) {
            MultipartFile file = nextNonEmpty(files, fileIndex);
            if (file != null) fileIndex = advanceFileIndex(files, fileIndex);

            ImageItemDto dto = dtos != null && i < dtos.size() ? dtos.get(i) : null;
            ImageAlbumItem existing = resolveExistingAlbumItem(
                    existingById, requestedIds, dto, i);

            if (!hasFile(file) && !hasImageSource(dto)
                    && (existing == null || !hasImageSource(existing))) {
                throw imageSourceRequired(i);
            }
        }
    }

    /**
     * Builds the final replacement list while reusing managed album entities by
     * ID. Omitting source fields for an existing item preserves its source and
     * extracted metadata.
     */
    private List<ImageAlbumItem> mergeAlbumItems(
            ImageCollection owner,
            ImageCollectionType type,
            List<ImageItemDto> dtos,
            List<MultipartFile> files
    ) throws IOException {
        Map<Long, ImageAlbumItem> existingById = albumItemsById(owner);
        Set<Long> requestedIds = new HashSet<>();

        int fileCount = nonEmptyFileCount(files);
        int dtoCount = dtos == null ? 0 : dtos.size();
        int max = Math.max(fileCount, dtoCount);

        validateAlbumItemCount(type, max);

        List<ImageAlbumItem> out = new ArrayList<>(max);
        int fileIndex = 0;

        for (int i = 0; i < max; i++) {
            MultipartFile file = nextNonEmpty(files, fileIndex);
            if (file != null) fileIndex = advanceFileIndex(files, fileIndex);

            ImageItemDto dto = dtos != null && i < dtos.size() ? dtos.get(i) : null;
            ImageAlbumItem item = resolveExistingAlbumItem(
                    existingById, requestedIds, dto, i);

            if (item == null) {
                item = new ImageAlbumItem();
                item.setImageCollection(owner);
            }

            item.setSortOrder(dto != null && dto.getSortOrder() != null
                    ? dto.getSortOrder()
                    : i);
            item.setCaptionCkb(dto != null ? trimOrNull(dto.getCaptionCkb()) : null);
            item.setCaptionKmr(dto != null ? trimOrNull(dto.getCaptionKmr()) : null);
            item.setDescriptionCkb(dto != null
                    ? tiptapHtmlProcessor.process(trimOrNull(dto.getDescriptionCkb()))
                    : null);
            item.setDescriptionKmr(dto != null
                    ? tiptapHtmlProcessor.process(trimOrNull(dto.getDescriptionKmr()))
                    : null);

            applyImageSourceForUpdate(item, file, dto);
            out.add(item);
        }

        return out;
    }

    private void applyImageSourceForUpdate(
            ImageAlbumItem item,
            MultipartFile file,
            ImageItemDto dto
    ) throws IOException {
        if (hasFile(file)) {
            applyImageSource(item, file, dto);
            return;
        }

        // No source fields means "keep the existing source" for a persisted row.
        if (!hasImageSource(dto)) {
            return;
        }

        String imageUrl = trimOrNull(dto.getImageUrl());
        String externalUrl = trimOrNull(dto.getExternalUrl());
        String embedUrl = trimOrNull(dto.getEmbedUrl());

        // A response-shaped update often repeats the unchanged source. Preserve
        // metadata in that case instead of clearing it unnecessarily.
        if (Objects.equals(imageUrl, item.getImageUrl())
                && Objects.equals(externalUrl, item.getExternalUrl())
                && Objects.equals(embedUrl, item.getEmbedUrl())) {
            return;
        }

        item.setImageUrl(imageUrl);
        item.setExternalUrl(externalUrl);
        item.setEmbedUrl(embedUrl);
        clearImageMetadata(item);
    }

    private Map<Long, ImageAlbumItem> albumItemsById(ImageCollection owner) {
        Map<Long, ImageAlbumItem> items = new LinkedHashMap<>();
        for (ImageAlbumItem item : owner.getImageAlbum()) {
            if (item.getId() != null) {
                items.put(item.getId(), item);
            }
        }
        return items;
    }

    private ImageAlbumItem resolveExistingAlbumItem(
            Map<Long, ImageAlbumItem> existingById,
            Set<Long> requestedIds,
            ImageItemDto dto,
            int index
    ) {
        if (dto == null || dto.getId() == null) {
            return null;
        }

        Long itemId = dto.getId();
        ImageAlbumItem existing = existingById.get(itemId);
        if (existing == null) {
            throw Errors.imageValidation("error.validation", Map.of(
                    "field", "imageAlbum[" + index + "].id",
                    "id", itemId,
                    "message", "وێنەکە لەم کۆمەڵەیەدا نەدۆزرایەوە"));
        }
        if (!requestedIds.add(itemId)) {
            throw Errors.imageValidation("error.validation", Map.of(
                    "field", "imageAlbum[" + index + "].id",
                    "id", itemId,
                    "message", "ئایدی وێنە نابێت دووبارە بێتەوە"));
        }
        return existing;
    }

    private boolean hasImageSource(ImageItemDto dto) {
        return dto != null && (!isBlank(dto.getImageUrl())
                || !isBlank(dto.getExternalUrl())
                || !isBlank(dto.getEmbedUrl()));
    }

    private boolean hasImageSource(ImageAlbumItem item) {
        return item != null && (!isBlank(item.getImageUrl())
                || !isBlank(item.getExternalUrl())
                || !isBlank(item.getEmbedUrl()));
    }

    private RuntimeException imageSourceRequired(int index) {
        return Errors.imageValidation("image.source.required", Map.of(
                "field", "imageAlbum[" + index + "]",
                "message",
                "هەر وێنەیەک پێویستی بە فایل یان لینکی ڕاستەقینە یان لینکی دەرەکی یان ئێمبێد هەیە"));
    }

    private void clearImageMetadata(ImageAlbumItem item) {
        item.setFileSizeBytes(null);
        item.setWidthPx(null);
        item.setHeightPx(null);
        item.setMimeType(null);
    }

    /**
     * Updated to automatically extract metadata when file is uploaded
     */
    private void applyImageSource(ImageAlbumItem item, MultipartFile file, ImageItemDto dto)
            throws IOException {

        if (hasFile(file)) {
            // Read bytes once ( reused for metadata extraction + upload)
            byte[] fileBytes = file.getBytes();

            // ─── AUTO EXTRACT METADATA HERE ───────────────────────────────
            extractAndSetImageMetadata(item, file, fileBytes);

            // Upload using same bytes (avoid reading stream twice)
            item.setImageUrl(s3Service.upload(fileBytes, file.getOriginalFilename(), file.getContentType()));
            item.setExternalUrl(null);
            item.setEmbedUrl(null);
            return;
        }

        // Handle external URLs (no metadata available for these)
        String s3  = dto != null ? trimOrNull(dto.getImageUrl())    : null;
        String ext = dto != null ? trimOrNull(dto.getExternalUrl()) : null;
        String emb = dto != null ? trimOrNull(dto.getEmbedUrl())    : null;

        if (isBlank(s3) && isBlank(ext) && isBlank(emb)) {
            throw Errors.imageValidation("image.source.required", Map.of(
                    "message",
                    "هەر وێنەیەک پێویستی بە فایل یان لینکی ڕاستەقینە یان لینکی دەرەکی یان ئێمبێد هەیە"));
        }

        item.setImageUrl(s3);
        item.setExternalUrl(ext);
        item.setEmbedUrl(emb);

        // Clear metadata fields for external/embed URLs (we can't extract from remote URLs)
        clearImageMetadata(item);
    }

    // =========================================================================
    // پشتڕاستکردنەوەی جۆری کۆمەڵە (Validation)
    // =========================================================================

    private void validateAlbumItemCount(ImageCollectionType type, int count) {
        switch (type) {
            case SINGLE -> {
                if (count != 1)
                    throw Errors.imageValidation("imageCollection.single.invalid",
                            Map.of("message", "جۆری SINGLE پێویستی بە تەنها ١ وێنەیە",
                                    "count", count));
            }
            case GALLERY -> {
                if (count < 1)
                    throw Errors.imageValidation("imageCollection.gallery.invalid",
                            Map.of("message", "جۆری GALLERY پێویستی بە لانیکەم ١ وێنەیە"));
            }
            case PHOTO_STORY -> {
                if (count < 2)
                    throw Errors.imageValidation("imageCollection.photoStory.invalid",
                            Map.of("message", "جۆری PHOTO_STORY پێویستی بە لانیکەم ٢ وێنەیە",
                                    "count", count));
            }
        }
    }

    // =========================================================================
    // یاریدەدەرەکانی ناوەڕۆک (Content Helpers)
    // =========================================================================

    private void applyContentByLanguages(
            ImageCollection entity,
            Set<Language> langs,
            LanguageContentDto ckb,
            LanguageContentDto kmr
    ) {
        Set<Language> safe = safeLangs(langs);
        entity.setCkbContent(safe.contains(Language.CKB) ? buildContent(ckb) : null);
        entity.setKmrContent(safe.contains(Language.KMR) ? buildContent(kmr) : null);
    }

    private ImageContent buildContent(LanguageContentDto dto) {
        if (dto == null) return null;
        if (isBlank(dto.getTitle()) && isBlank(dto.getDescription())
                && isBlank(dto.getLocation()) && isBlank(dto.getCollectedBy())) return null;
        return ImageContent.builder()
                .title(trimOrNull(dto.getTitle()))
                .description(tiptapHtmlProcessor.process(trimOrNull(dto.getDescription())))
                .location(trimOrNull(dto.getLocation()))
                .collectedBy(trimOrNull(dto.getCollectedBy()))
                .build();
    }

    private void applyContentForUpdate(
            ImageCollection entity,
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

    private ImageContent mergeContent(ImageContent existing, LanguageContentDto dto) {
        if (existing == null) return buildContent(dto);
        if (dto.getTitle() != null) existing.setTitle(trimOrNull(dto.getTitle()));
        if (dto.getDescription() != null) {
            existing.setDescription(
                    tiptapHtmlProcessor.process(trimOrNull(dto.getDescription())));
        }
        if (dto.getLocation() != null) {
            existing.setLocation(trimOrNull(dto.getLocation()));
        }
        if (dto.getCollectedBy() != null) {
            existing.setCollectedBy(trimOrNull(dto.getCollectedBy()));
        }
        return existing;
    }

    // =========================================================================
    // پشتڕاستکردنەوەی دروستکردن (Create Validation)
    // =========================================================================

    private void validateCreate(CreateRequest dto, MultipartFile ckbCoverImage) {
        if (dto == null)
            throw Errors.imageValidation("error.validation", Map.of("field", "data"));
        if (dto.getCollectionType() == null)
            throw Errors.imageValidation("imageCollection.type.required",
                    Map.of("field", "collectionType"));
        if (safeLangs(dto.getContentLanguages()).isEmpty())
            throw Errors.imageValidation("imageCollection.languages.required",
                    Map.of("field", "contentLanguages"));

        boolean hasCoverFile = hasFile(ckbCoverImage);
        boolean hasCoverUrl  = !isBlank(dto.getCkbCoverUrl())
                || !isBlank(dto.getKmrCoverUrl())
                || !isBlank(dto.getHoverCoverUrl());
        if (!hasCoverFile && !hasCoverUrl) {
            throw Errors.imageValidation("imageCollection.cover.required", Map.of(
                    "field", "ckbCoverImage | ckbCoverUrl | kmrCoverUrl | hoverCoverUrl"));
        }
    }

    // =========================================================================
    // گۆڕین بۆ Response (Entity to DTO)
    // =========================================================================

    private Response toResponse(ImageCollection entity) {
        Response.ResponseBuilder b = Response.builder()
                .id(entity.getId())
                .slugCkb(entity.getSlugCkb())
                .slugKmr(entity.getSlugKmr())
                .collectionType(entity.getCollectionType())
                .ckbCoverUrl(entity.getCkbCoverUrl())
                .kmrCoverUrl(entity.getKmrCoverUrl())
                .hoverCoverUrl(entity.getHoverCoverUrl())
                .featureImageUrl(entity.getFeatureImageUrl())
                .topicId(entity.getTopic()      != null ? entity.getTopic().getId()      : null)
                .topicNameCkb(entity.getTopic() != null ? entity.getTopic().getNameCkb() : null)
                .topicNameKmr(entity.getTopic() != null ? entity.getTopic().getNameKmr() : null)
                .publishmentDate(entity.getPublishmentDate())
                // ← copy into plain Set — prevents LazyInitializationException
                .contentLanguages(entity.getContentLanguages() != null
                        ? new LinkedHashSet<>(entity.getContentLanguages())
                        : new LinkedHashSet<>())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt());

        if (entity.getCkbContent() != null) {
            b.ckbContent(LanguageContentDto.builder()
                    .title(entity.getCkbContent().getTitle())
                    .description(entity.getCkbContent().getDescription())
                    .location(entity.getCkbContent().getLocation())
                    .collectedBy(entity.getCkbContent().getCollectedBy())
                    .build());
        }

        if (entity.getKmrContent() != null) {
            b.kmrContent(LanguageContentDto.builder()
                    .title(entity.getKmrContent().getTitle())
                    .description(entity.getKmrContent().getDescription())
                    .location(entity.getKmrContent().getLocation())
                    .collectedBy(entity.getKmrContent().getCollectedBy())
                    .build());
        }

        // ← copy into plain Set
        b.tags(BilingualSet.builder()
                .ckb(new LinkedHashSet<>(safeSet(entity.getTagsCkb())))
                .kmr(new LinkedHashSet<>(safeSet(entity.getTagsKmr())))
                .build());
        b.keywords(BilingualSet.builder()
                .ckb(new LinkedHashSet<>(safeSet(entity.getKeywordsCkb())))
                .kmr(new LinkedHashSet<>(safeSet(entity.getKeywordsKmr())))
                .build());

        // ← copy into plain List with NEW METADATA FIELDS included
        List<ImageItemDto> items = entity.getImageAlbum() == null ? List.of()
                : new ArrayList<>(entity.getImageAlbum()).stream()
                .sorted(Comparator.comparing(
                        i -> i.getSortOrder() != null ? i.getSortOrder() : 0))
                .map(i -> ImageItemDto.builder()
                        .id(i.getId())
                        .imageUrl(i.getImageUrl())
                        .externalUrl(i.getExternalUrl())
                        .embedUrl(i.getEmbedUrl())
                        .captionCkb(i.getCaptionCkb())
                        .captionKmr(i.getCaptionKmr())
                        .descriptionCkb(i.getDescriptionCkb())
                        .descriptionKmr(i.getDescriptionKmr())
                        .sortOrder(i.getSortOrder())
                        // NEW: Auto-extracted metadata fields
                        .fileSizeBytes(i.getFileSizeBytes())
                        .widthPx(i.getWidthPx())
                        .heightPx(i.getHeightPx())
                        .mimeType(i.getMimeType())
                        .aspectRatio(i.getAspectRatio())        // transient calculated
                        .humanReadableSize(i.getHumanReadableSize()) // transient formatted
                        .build())
                .collect(Collectors.toList());
        b.imageAlbum(items);

        return b.build();
    }

    private Pageable featuredPageable(int page, int size) {
        return PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.ASC, "featuredOrder")
                        .and(Sort.by(Sort.Direction.DESC, "id"))
        );
    }

    // =========================================================================
    // تۆمارکردنی چالاکی (Logging)
    // =========================================================================

    private void createLog(Long id, String title, String action, String details) {
        try {
            imageCollectionLogRepository.save(ImageCollectionLog.builder()
                    .imageCollectionId(id)
                    .collectionTitle(title)
                    .action(action)
                    .details(details)
                    .performedBy("system")
                    .timestamp(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("شکستی تۆمارکردنی لۆگی کۆمەڵەی وێنە | id={}", id, e);
        }
    }

    // =========================================================================
    // یاریدەدەرە گشتییەکان (General Utilities)
    // =========================================================================

    private String titleOf(ImageCollection c) {
        if (c == null) return "";
        if (c.getCkbContent() != null && !isBlank(c.getCkbContent().getTitle()))
            return c.getCkbContent().getTitle();
        if (c.getKmrContent() != null && !isBlank(c.getKmrContent().getTitle()))
            return c.getKmrContent().getTitle();
        return "کۆمەڵەی وێنە#" + c.getId();
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
        for (String s : in) {
            String t = trimOrNull(s);
            if (t != null) out.add(t);
        }
        return out;
    }

    private boolean hasUploads(List<MultipartFile> files) {
        return nonEmptyFileCount(files) > 0;
    }

    private int nonEmptyFileCount(List<MultipartFile> files) {
        return files == null ? 0
                : (int) files.stream().filter(this::hasFile).count();
    }

    private MultipartFile nextNonEmpty(List<MultipartFile> files, int start) {
        if (files == null) return null;
        for (int i = start; i < files.size(); i++) {
            if (files.get(i) != null && !files.get(i).isEmpty()) return files.get(i);
        }
        return null;
    }

    private int advanceFileIndex(List<MultipartFile> files, int start) {
        if (files == null) return start;
        for (int i = start; i < files.size(); i++) {
            if (files.get(i) != null && !files.get(i).isEmpty()) return i + 1;
        }
        return files.size();
    }
}
