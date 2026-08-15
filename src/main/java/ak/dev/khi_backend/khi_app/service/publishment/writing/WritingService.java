package ak.dev.khi_backend.khi_app.service.publishment.writing;

import ak.dev.khi_backend.khi_app.dto.publishment.writing.WritingDtos.*;
import ak.dev.khi_backend.khi_app.enums.Language;
import ak.dev.khi_backend.khi_app.enums.publishment.BookGenre;
import ak.dev.khi_backend.khi_app.exceptions.BadRequestException;
import ak.dev.khi_backend.khi_app.exceptions.NotFoundException;
import ak.dev.khi_backend.khi_app.exceptions.Errors;
import ak.dev.khi_backend.khi_app.model.publishment.topic.PublishmentTopic;
import ak.dev.khi_backend.khi_app.model.publishment.writing.Writing;
import ak.dev.khi_backend.khi_app.model.publishment.writing.WritingContent;
import ak.dev.khi_backend.khi_app.model.publishment.writing.WritingLog;
import ak.dev.khi_backend.khi_app.repository.publishment.topic.PublishmentTopicRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.writing.WritingLogRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.writing.WritingRepository;
import ak.dev.khi_backend.khi_app.service.S3Service;
import ak.dev.khi_backend.khi_app.service.media.TiptapHtmlProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WritingService {

    private static final String ENTITY_TYPE = "WRITING";

    private final WritingRepository          writingRepository;
    private final WritingLogRepository       writingLogRepository;
    private final PublishmentTopicRepository topicRepository;
    private final S3Service                  s3Service;
    private final ObjectMapper               objectMapper;
    private final TiptapHtmlProcessor        tiptapHtmlProcessor;

    // =========================================================================
    // دروستکردن
    // =========================================================================

    @Transactional
    public Response addWriting(CreateRequest request,
                               MultipartFile ckbCoverImage,
                               MultipartFile kmrCoverImage,
                               MultipartFile hoverCoverImage,
                               MultipartFile ckbBookFile,
                               MultipartFile kmrBookFile) {

        log.info("دروستکردنی نووسراو: {}", getCombinedTitle(request));

        validate(request, true);

        String ckbCoverUrl   = uploadOrFallback(ckbCoverImage,   request.getCkbCoverUrl(),   "وێنەی بەرگی CKB");
        String kmrCoverUrl   = uploadOrFallback(kmrCoverImage,   request.getKmrCoverUrl(),   "وێنەی بەرگی KMR");
        String hoverCoverUrl = uploadOrFallback(hoverCoverImage, request.getHoverCoverUrl(), "وێنەی هاڤەر");

        String ckbFileUrl = uploadFile(ckbBookFile, "فایلی کتێبی CKB");
        String kmrFileUrl = uploadFile(kmrBookFile, "فایلی کتێبی KMR");

        PublishmentTopic topic = resolveTopic(request.getTopicId(), request.getNewTopic());

        Writing parentBook  = null;
        String  seriesId    = request.getSeriesId();
        Double  seriesOrder = request.getSeriesOrder();

        if (request.getParentBookId() != null) {
            parentBook  = findOrThrow(request.getParentBookId(), "parent_book.not_found");
            seriesId    = parentBook.getSeriesId();
            if (seriesOrder == null) {
                Double maxOrder = writingRepository.findMaxSeriesOrder(seriesId);
                seriesOrder = (maxOrder != null ? maxOrder : 0.0) + 1.0;
            }
        }

        Writing writing = Writing.builder()
                .ckbCoverUrl(ckbCoverUrl)
                .kmrCoverUrl(kmrCoverUrl)
                .hoverCoverUrl(hoverCoverUrl)
                .topic(topic)
                .bookGenres(new LinkedHashSet<>(safeGenres(request.getBookGenres())))
                .contentLanguages(new LinkedHashSet<>(safeLangs(request.getContentLanguages())))
                .publishedByInstitute(request.isPublishedByInstitute())
                .tagsCkb(new LinkedHashSet<>(safeSet(request.getTags()     != null ? request.getTags().getCkb()     : null)))
                .tagsKmr(new LinkedHashSet<>(safeSet(request.getTags()     != null ? request.getTags().getKmr()     : null)))
                .keywordsCkb(new LinkedHashSet<>(safeSet(request.getKeywords() != null ? request.getKeywords().getCkb() : null)))
                .keywordsKmr(new LinkedHashSet<>(safeSet(request.getKeywords() != null ? request.getKeywords().getKmr() : null)))
                .seriesId(seriesId)
                .seriesName(request.getSeriesName())
                .seriesOrder(seriesOrder)
                .parentBook(parentBook)
                .build();

        applyContent(writing, request, ckbFileUrl, kmrFileUrl);

        Writing saved = writingRepository.save(writing);
        updateSeriesCount(saved.getSeriesId());

        logAction(saved, "CREATED", "نووسراو '" + getCombinedTitle(saved) + "' دروستکرا");
        log.info("نووسراو دروستکرا — id={}, زنجیرە={}", saved.getId(), saved.getSeriesId());
        return mapToResponse(saved);
    }

    // =========================================================================
    // نوێکردنەوە
    // =========================================================================

    @Transactional
    public Response updateWriting(Long id,
                                  UpdateRequest request,
                                  MultipartFile ckbCoverImage,
                                  MultipartFile kmrCoverImage,
                                  MultipartFile hoverCoverImage,
                                  MultipartFile ckbBookFile,
                                  MultipartFile kmrBookFile) {

        log.info("نوێکردنەوەی نووسراو id={}", id);

        Writing writing = findOrThrow(id, "writing.not_found");

        validate(request);

        // Resolve references before any S3 or Tiptap upload. A failed topic or
        // parent lookup must not leave an orphaned object in S3.
        PublishmentTopic resolvedTopic = null;
        if (!Boolean.TRUE.equals(request.getClearTopic())
                && (request.getTopicId() != null || request.getNewTopic() != null)) {
            resolvedTopic = resolveTopic(request.getTopicId(), request.getNewTopic());
        }
        Writing resolvedParent = request.getParentBookId() != null
                ? findOrThrow(request.getParentBookId(), "parent_book.not_found")
                : null;

        writing.setCkbCoverUrl(resolveUpdate(ckbCoverImage,   request.getCkbCoverUrl(),   writing.getCkbCoverUrl()));
        writing.setKmrCoverUrl(resolveUpdate(kmrCoverImage,   request.getKmrCoverUrl(),   writing.getKmrCoverUrl()));
        writing.setHoverCoverUrl(resolveUpdate(hoverCoverImage, request.getHoverCoverUrl(), writing.getHoverCoverUrl()));

        String ckbFileUrl = uploadFile(ckbBookFile, "فایلی کتێبی CKB");
        String kmrFileUrl = uploadFile(kmrBookFile, "فایلی کتێبی KMR");

        if (Boolean.TRUE.equals(request.getClearTopic())) {
            writing.setTopic(null);
        } else if (resolvedTopic != null) {
            writing.setTopic(resolvedTopic);
        }

        // ─── Book Genres ─────────────────────────────────────────────────────
        if (request.getBookGenres() != null && !request.getBookGenres().isEmpty()) {
            writing.getBookGenres().clear();
            writing.getBookGenres().addAll(request.getBookGenres());
        }

        if (request.getPublishedByInstitute() != null) writing.setPublishedByInstitute(request.getPublishedByInstitute());

        if (request.getContentLanguages() != null && !request.getContentLanguages().isEmpty()) {
            writing.setContentLanguages(new LinkedHashSet<>(request.getContentLanguages()));
        }
        applyContent(writing, request, ckbFileUrl, kmrFileUrl);

        replaceBilingualSets(writing, request);

        String oldSeriesId = writing.getSeriesId();
        if (request.getSeriesName()  != null) writing.setSeriesName(request.getSeriesName());
        if (request.getSeriesOrder() != null) writing.setSeriesOrder(request.getSeriesOrder());
        if (resolvedParent != null) {
            writing.setParentBook(resolvedParent);
            writing.setSeriesId(resolvedParent.getSeriesId());
        }

        Writing updated = writingRepository.save(writing);

        if (oldSeriesId != null && !oldSeriesId.equals(updated.getSeriesId())) {
            updateSeriesCount(oldSeriesId);
        }
        updateSeriesCount(updated.getSeriesId());

        logAction(updated, "UPDATED", "نووسراو '" + getCombinedTitle(updated) + "' نوێکرایەوە");
        return mapToResponse(updated);
    }

    // =========================================================================
    // سڕینەوە
    // =========================================================================

    @Transactional
    public void deleteWriting(Long id) {
        log.info("سڕینەوەی نووسراو id={}", id);

        if (id == null) return;

        Writing writing = writingRepository.findByIdWithDetails(id).orElse(null);
        if (writing == null) {
            log.debug("Writing delete ignored; id={} does not exist", id);
            return;
        }

        String seriesId  = writing.getSeriesId();
        String title     = getCombinedTitle(writing);
        Long   writingId = writing.getId();

        // A writing can be referenced by both its audit history and child books.
        // Detach those nullable relationships before issuing the hard delete so
        // PostgreSQL does not reject it with a foreign-key conflict.
        List<Writing> childBooks = new ArrayList<>(writing.getSeriesBooks());
        childBooks.forEach(child -> child.setParentBook(null));
        if (!childBooks.isEmpty()) {
            writingRepository.saveAll(childBooks);
            writingRepository.flush();
        }
        writingLogRepository.detachFromWriting(writingId);

        WritingLog deletionLog = WritingLog.builder()
                .writing(null)
                .writingId(writingId)
                .action("DELETED")
                .actorId("system")
                .actorName("System")
                .details("نووسراو '" + title + "' سڕایەوە")
                .createdAt(LocalDateTime.now())
                .build();
        writingLogRepository.saveAndFlush(deletionLog);

        writingRepository.delete(writing);
        writingRepository.flush();

        updateSeriesCount(seriesId);
    }

    // =========================================================================
    // خوێندنەوە
    // =========================================================================

    @Transactional(readOnly = true)
    public Page<Response> getAllWritings(Pageable pageable) {
        return writingRepository.findAllWithTopic(pageable).map(this::mapToResponse);
    }

    @Transactional(readOnly = true)
    public Page<Response> getFeatured(int page, int size) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.ASC, "featuredOrder")
                        .and(Sort.by(Sort.Direction.DESC, "id"))
        );
        return writingRepository.findFeaturedWithTopic(pageable).map(this::mapToResponse);
    }

    @Transactional(readOnly = true)
    public Response getWritingById(Long id) {
        return mapToResponse(findOrThrow(id, "writing.not_found"));
    }

    // =========================================================================
    // زنجیرە (Series)
    // =========================================================================

    @Transactional
    public Response linkBookToSeries(LinkToSeriesRequest request) {
        Writing book   = findOrThrow(request.getBookId(),       "writing.not_found");
        Writing parent = findOrThrow(request.getParentBookId(), "parent_book.not_found");

        book.setParentBook(parent);
        book.setSeriesId(parent.getSeriesId());
        book.setSeriesOrder(request.getSeriesOrder());
        book.setSeriesName(request.getSeriesName() != null ? request.getSeriesName() : parent.getSeriesName());

        Writing updated = writingRepository.save(book);
        updateSeriesCount(updated.getSeriesId());
        logAction(updated, "LINKED_TO_SERIES", "لکێندراوە بە زنجیرە " + parent.getSeriesId());
        return mapToResponse(updated);
    }

    @Transactional(readOnly = true)
    public SeriesResponse getSeriesBooks(String seriesId) {
        List<Writing> books = writingRepository.findBySeriesIdOrderBySeriesOrderAsc(seriesId);
        if (books.isEmpty()) {
            throw new NotFoundException("series.not_found", Map.of("seriesId", seriesId));
        }

        String seriesName = books.get(0).getEffectiveSeriesName();
        List<SeriesBookSummary> summaries = books.stream()
                .map(b -> SeriesBookSummary.builder()
                        .id(b.getId())
                        .titleCkb(b.getCkbContent() != null ? b.getCkbContent().getTitle() : null)
                        .titleKmr(b.getKmrContent() != null ? b.getKmrContent().getTitle() : null)
                        .seriesOrder(b.getSeriesOrder())
                        .createdAt(b.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        return SeriesResponse.builder()
                .seriesId(seriesId)
                .seriesName(seriesName)
                .totalBooks(books.size())
                .books(summaries)
                .build();
    }

    @Transactional(readOnly = true)
    public Page<Response> getAllSeriesParents(Pageable pageable) {
        return writingRepository.findSeriesParents(pageable).map(this::mapToResponse);
    }

    // =========================================================================
    // گەڕان
    // =========================================================================

    @Transactional(readOnly = true)
    public Page<Response> searchByWriter(String writerName, String language, Pageable pageable) {
        if (isBlank(writerName)) throw new BadRequestException("search.writer.required", Map.of("field", "writerName"));
        String q = writerName.trim();
        Page<Writing> results;
        if ("ckb".equalsIgnoreCase(language))      results = writingRepository.findByWriterCkbContainingIgnoreCase(q, pageable);
        else if ("kmr".equalsIgnoreCase(language)) results = writingRepository.findByWriterKmrContainingIgnoreCase(q, pageable);
        else                                       results = writingRepository.findByWriterInBothLanguages(q, pageable);
        return results.map(this::mapToResponse);
    }

    @Transactional(readOnly = true)
    public Page<Response> searchByTag(String tag, String language, Pageable pageable) {
        if (isBlank(tag)) throw new BadRequestException("search.tag.required", Map.of("field", "tag"));
        String q = tag.trim();
        Page<Writing> results;
        if ("ckb".equalsIgnoreCase(language))      results = writingRepository.findByTagCkb(q, pageable);
        else if ("kmr".equalsIgnoreCase(language)) results = writingRepository.findByTagKmr(q, pageable);
        else                                       results = writingRepository.findByTagInBothLanguages(q, pageable);
        return results.map(this::mapToResponse);
    }

    @Transactional(readOnly = true)
    public Page<Response> searchByKeyword(String keyword, String language, Pageable pageable) {
        if (isBlank(keyword)) throw new BadRequestException("search.keyword.required", Map.of("field", "keyword"));
        String q = keyword.trim();
        Page<Writing> results;
        if ("ckb".equalsIgnoreCase(language))      results = writingRepository.findByKeywordCkb(q, pageable);
        else if ("kmr".equalsIgnoreCase(language)) results = writingRepository.findByKeywordKmr(q, pageable);
        else                                       results = writingRepository.findByKeywordInBothLanguages(q, pageable);
        return results.map(this::mapToResponse);
    }

    // =========================================================================
    // بابەت
    // =========================================================================

    private PublishmentTopic resolveTopic(Long topicId, TopicPayload newTopic) {
        if (topicId != null) {
            return topicRepository.findById(topicId)
                    .orElseThrow(() -> Errors.notFound("topic.not_found", Map.of("topicId", topicId)));
        }
        if (newTopic != null && (!isBlank(newTopic.getNameCkb()) || !isBlank(newTopic.getNameKmr()))) {
            PublishmentTopic created = PublishmentTopic.builder()
                    .entityType(ENTITY_TYPE)
                    .nameCkb(trimOrNull(newTopic.getNameCkb()))
                    .nameKmr(trimOrNull(newTopic.getNameKmr()))
                    .build();
            created = topicRepository.save(created);
            log.info("بابەتی inline دروستکرا — id={}, ckb='{}', kmr='{}'",
                    created.getId(), created.getNameCkb(), created.getNameKmr());
            return created;
        }
        return null;
    }

    // =========================================================================
    // ژماری زنجیرە
    // =========================================================================

    @Transactional
    protected void updateSeriesCount(String seriesId) {
        if (isBlank(seriesId)) return;
        Long count = writingRepository.countBySeriesId(seriesId);
        List<Writing> books = writingRepository.findBySeriesIdOrderBySeriesOrderAsc(seriesId);
        books.forEach(b -> b.setSeriesTotalBooks(count.intValue()));
        writingRepository.saveAll(books);
    }

    // =========================================================================
    // پشتڕاستکردنەوە
    // =========================================================================

    private void validate(CreateRequest request, boolean isCreate) {
        if (request.getContentLanguages() == null || request.getContentLanguages().isEmpty()) {
            throw new BadRequestException("writing.languages.required", Map.of("field", "contentLanguages"));
        }
        if (request.getBookGenres() == null || request.getBookGenres().isEmpty()) {
            throw new BadRequestException("writing.genres.required", Map.of("field", "bookGenres"));
        }
        for (Language lang : request.getContentLanguages()) {
            LanguageContentDto content = lang == Language.CKB ? request.getCkbContent() : request.getKmrContent();
            if (content == null) {
                throw new BadRequestException("writing.content.missing",
                        Map.of("language", lang, "message", "ناوەڕۆک بۆ " + lang + " دیاری نەکراوە"));
            }
            if (isCreate && isBlank(content.getTitle())) {
                throw new BadRequestException("writing.title.required",
                        Map.of("language", lang, "message", "ناونیشان بۆ " + lang + " پێویستە"));
            }
        }
    }

    private void validate(UpdateRequest request) {
        if (request.getContentLanguages() == null) return;
        for (Language lang : request.getContentLanguages()) {
            LanguageContentDto content = lang == Language.CKB ? request.getCkbContent() : request.getKmrContent();
            if (content == null) throw new BadRequestException("writing.content.missing", Map.of("language", lang));
        }
    }

    // =========================================================================
    // ناوەڕۆک
    // =========================================================================

    private void applyContent(Writing writing, CreateRequest request, String ckbFileUrl, String kmrFileUrl) {
        if (request.getContentLanguages().contains(Language.CKB)) {
            writing.setCkbContent(buildContent(request.getCkbContent(), ckbFileUrl));
        }
        if (request.getContentLanguages().contains(Language.KMR)) {
            writing.setKmrContent(buildContent(request.getKmrContent(), kmrFileUrl));
        }
    }

    private void applyContent(Writing writing, UpdateRequest request, String ckbFileUrl, String kmrFileUrl) {
        if (request.getCkbContent() != null) {
            writing.setCkbContent(mergeContent(writing.getCkbContent(), request.getCkbContent(), ckbFileUrl));
        }
        if (request.getKmrContent() != null) {
            writing.setKmrContent(mergeContent(writing.getKmrContent(), request.getKmrContent(), kmrFileUrl));
        }
    }

    private WritingContent buildContent(LanguageContentDto dto, String fileUrl) {
        if (dto == null) return null;
        return WritingContent.builder()
                .title(trimOrNull(dto.getTitle()))
                .description(tiptapHtmlProcessor.process(trimOrNull(dto.getDescription())))
                .writer(trimOrNull(dto.getWriter()))
                .fileUrl(fileUrl != null ? fileUrl : trimOrNull(dto.getFileUrl()))
                .fileFormat(dto.getFileFormat())
                .fileSizeBytes(dto.getFileSizeBytes())
                .pageCount(dto.getPageCount())
                .genre(trimOrNull(dto.getGenre()))
                .build();
    }

    private WritingContent mergeContent(WritingContent existing, LanguageContentDto dto, String fileUrl) {
        if (existing == null) return buildContent(dto, fileUrl);
        if (dto.getTitle()         != null) existing.setTitle(trimOrNull(dto.getTitle()));
        if (dto.getDescription()   != null) existing.setDescription(tiptapHtmlProcessor.process(trimOrNull(dto.getDescription())));
        if (dto.getWriter()        != null) existing.setWriter(trimOrNull(dto.getWriter()));
        // No new multipart file and no fileUrl in the DTO means retain the
        // persisted book source during metadata-only updates.
        if (fileUrl                != null) existing.setFileUrl(fileUrl);
        else if (dto.getFileUrl()  != null) existing.setFileUrl(trimOrNull(dto.getFileUrl()));
        if (dto.getFileFormat()    != null) existing.setFileFormat(dto.getFileFormat());
        if (dto.getFileSizeBytes() != null) existing.setFileSizeBytes(dto.getFileSizeBytes());
        if (dto.getPageCount()     != null) existing.setPageCount(dto.getPageCount());
        if (dto.getGenre()         != null) existing.setGenre(trimOrNull(dto.getGenre()));
        return existing;
    }

    private void replaceBilingualSets(Writing writing, UpdateRequest request) {
        if (request.getTags() != null) {
            if (request.getTags().getCkb() != null) { writing.getTagsCkb().clear(); writing.getTagsCkb().addAll(cleanStrings(request.getTags().getCkb())); }
            if (request.getTags().getKmr() != null) { writing.getTagsKmr().clear(); writing.getTagsKmr().addAll(cleanStrings(request.getTags().getKmr())); }
        }
        if (request.getKeywords() != null) {
            if (request.getKeywords().getCkb() != null) { writing.getKeywordsCkb().clear(); writing.getKeywordsCkb().addAll(cleanStrings(request.getKeywords().getCkb())); }
            if (request.getKeywords().getKmr() != null) { writing.getKeywordsKmr().clear(); writing.getKeywordsKmr().addAll(cleanStrings(request.getKeywords().getKmr())); }
        }
    }

    // =========================================================================
    // گۆڕین بۆ Response
    // =========================================================================

    private Response mapToResponse(Writing w) {
        Response r = Response.builder()
                .id(w.getId())
                .contentLanguages(w.getContentLanguages() != null
                        ? new LinkedHashSet<>(w.getContentLanguages()) : new LinkedHashSet<>())
                .ckbCoverUrl(w.getCkbCoverUrl())
                .kmrCoverUrl(w.getKmrCoverUrl())
                .hoverCoverUrl(w.getHoverCoverUrl())
                .featureImageUrl(w.getFeatureImageUrl())
                .topic(w.getTopic() != null
                        ? TopicInfo.builder()
                        .id(w.getTopic().getId())
                        .nameCkb(w.getTopic().getNameCkb())
                        .nameKmr(w.getTopic().getNameKmr())
                        .build()
                        : null)
                .bookGenres(w.getBookGenres() != null
                        ? new LinkedHashSet<>(w.getBookGenres()) : new LinkedHashSet<>())
                .publishedByInstitute(w.isPublishedByInstitute())
                .createdAt(w.getCreatedAt())
                .updatedAt(w.getUpdatedAt())
                .build();

        if (w.getCkbContent() != null) {
            WritingContent c = w.getCkbContent();
            r.setCkbContent(LanguageContentDto.builder()
                    .title(c.getTitle()).description(c.getDescription()).writer(c.getWriter())
                    .fileUrl(c.getFileUrl()).fileFormat(c.getFileFormat())
                    .fileSizeBytes(c.getFileSizeBytes()).pageCount(c.getPageCount()).genre(c.getGenre())
                    .build());
        }

        if (w.getKmrContent() != null) {
            WritingContent c = w.getKmrContent();
            r.setKmrContent(LanguageContentDto.builder()
                    .title(c.getTitle()).description(c.getDescription()).writer(c.getWriter())
                    .fileUrl(c.getFileUrl()).fileFormat(c.getFileFormat())
                    .fileSizeBytes(c.getFileSizeBytes()).pageCount(c.getPageCount()).genre(c.getGenre())
                    .build());
        }

        r.setTags(BilingualSet.builder()
                .ckb(new LinkedHashSet<>(safeSet(w.getTagsCkb())))
                .kmr(new LinkedHashSet<>(safeSet(w.getTagsKmr())))
                .build());

        r.setKeywords(BilingualSet.builder()
                .ckb(new LinkedHashSet<>(safeSet(w.getKeywordsCkb())))
                .kmr(new LinkedHashSet<>(safeSet(w.getKeywordsKmr())))
                .build());

        if (w.getSeriesId() != null) {
            r.setSeriesInfo(SeriesInfoDto.builder()
                    .seriesId(w.getSeriesId())
                    .seriesName(w.getSeriesName())
                    .seriesOrder(w.getSeriesOrder())
                    .parentBookId(w.getParentBook() != null ? w.getParentBook().getId() : null)
                    .totalBooks(w.getSeriesTotalBooks())
                    .isParent(w.isSeriesParent())
                    .build());
        }

        return r;
    }

    // =========================================================================
    // یاریدەدەرەکانی ناردنی فایل
    // =========================================================================

    private String uploadFile(MultipartFile file, String description) {
        if (file == null || file.isEmpty()) return null;
        try {
            String url = s3Service.upload(file.getBytes(), file.getOriginalFilename(), file.getContentType());
            log.info("{} نێردرا → {}", description, url);
            return url;
        } catch (IOException e) {
            throw new BadRequestException("media.upload.failed",
                    Map.of("description", description, "error", e.getMessage()));
        }
    }

    private String uploadOrFallback(MultipartFile file, String urlFallback, String description) {
        String uploaded = uploadFile(file, description);
        return uploaded != null ? uploaded : trimOrNull(urlFallback);
    }

    private String resolveUpdate(MultipartFile file, String requestUrl, String existing) {
        String uploaded = uploadFile(file, "وێنەی بەرگ");
        if (uploaded != null) return uploaded;
        if (requestUrl != null) { String t = requestUrl.trim(); return t.isEmpty() ? null : t; }
        return existing;
    }

    // =========================================================================
    // تۆمارکردنی چالاکی
    // =========================================================================

    private void logAction(Writing writing, String action, String details) {
        writingLogRepository.save(WritingLog.builder()
                .writing(writing)
                .writingId(writing.getId())
                .action(action)
                .actorId("system")
                .actorName("System")
                .details(details)
                .createdAt(LocalDateTime.now())
                .build());
    }

    // =========================================================================
    // یاریدەدەرەکان
    // =========================================================================

    private Writing findOrThrow(Long id, String errorCode) {
        return writingRepository.findByIdWithDetails(id)
                .orElseThrow(() -> {
                    if ("writing.not_found".equals(errorCode)) return Errors.writingNotFound(id);
                    return Errors.notFound(errorCode, Map.of("id", id));
                });
    }

    private String getCombinedTitle(Writing w) {
        if (w.getCkbContent() != null && !isBlank(w.getCkbContent().getTitle())) return w.getCkbContent().getTitle();
        if (w.getKmrContent() != null && !isBlank(w.getKmrContent().getTitle())) return w.getKmrContent().getTitle();
        return "ناونیشانی نەناسراو";
    }

    private String getCombinedTitle(CreateRequest r) {
        if (r.getCkbContent() != null && !isBlank(r.getCkbContent().getTitle())) return r.getCkbContent().getTitle();
        if (r.getKmrContent() != null && !isBlank(r.getKmrContent().getTitle())) return r.getKmrContent().getTitle();
        return "ناونیشانی نەناسراو";
    }

    private boolean isBlank(String s)    { return s == null || s.isBlank(); }
    private String trimOrNull(String s)   { if (s == null) return null; String t = s.trim(); return t.isEmpty() ? null : t; }
    private Set<Language> safeLangs(Set<Language> l) { return l == null ? Set.of() : l; }
    private Set<BookGenre> safeGenres(Set<BookGenre> g) { return g == null ? Set.of() : g; }
    private <T> Set<T> safeSet(Set<T> s)             { return s == null ? Set.of() : s; }
    private Set<String> cleanStrings(Set<String> in) {
        if (in == null || in.isEmpty()) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : in) { if (s != null && !s.isBlank()) out.add(s.trim()); }
        return out;
    }
}
