package ak.dev.khi_backend.khi_app.service.publishment.writing;

import ak.dev.khi_backend.khi_app.dto.publishment.writing.WritingDtos.*;
import ak.dev.khi_backend.khi_app.enums.Language;
import ak.dev.khi_backend.khi_app.exceptions.BadRequestException;
import ak.dev.khi_backend.khi_app.exceptions.NotFoundException;
import ak.dev.khi_backend.khi_app.exceptions.Errors;
import ak.dev.khi_backend.khi_app.model.publishment.topic.PublishmentTopic;
import ak.dev.khi_backend.khi_app.model.publishment.writing.Writing;
import ak.dev.khi_backend.khi_app.model.publishment.writing.WritingContent;
import ak.dev.khi_backend.khi_app.model.publishment.writing.WritingPerson;
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
    private final BookGenreService           bookGenreService;
    private final S3Service                  s3Service;
    private final ObjectMapper               objectMapper;
    private final TiptapHtmlProcessor        tiptapHtmlProcessor;
    private final ak.dev.khi_backend.khi_app.service.media.MediaMetadataService mediaMetadata;

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
        if (request == null) request = new CreateRequest();

        log.info("دروستکردنی نووسراو: {}", getCombinedTitle(request));

        validate(request, true);

        String ckbCoverUrl   = uploadOrFallback(ckbCoverImage,   request.getCkbCoverUrl(),   "وێنەی بەرگی CKB");
        String kmrCoverUrl   = uploadOrFallback(kmrCoverImage,   request.getKmrCoverUrl(),   "وێنەی بەرگی KMR");
        String hoverCoverUrl = uploadOrFallback(hoverCoverImage, request.getHoverCoverUrl(), "وێنەی هاڤەر");

        String ckbFileUrl = uploadFile(ckbBookFile, "فایلی کتێبی CKB");
        String kmrFileUrl = uploadFile(kmrBookFile, "فایلی کتێبی KMR");

        Set<PublishmentTopic> topics = resolveTopics(
                request.getTopicIds(), request.getNewTopics(),
                request.getTopicId(), request.getNewTopic());

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
                .topics(topics)
                .genres(bookGenreService.resolve(request.getGenreIds(), request.getBookGenres()))
                .contentLanguages(new LinkedHashSet<>(safeLangs(request.getContentLanguages())))
                .bookLanguageCkb(request.getBookLanguageCkb())
                .bookLanguageKmr(request.getBookLanguageKmr())
                .publishedByInstitute(request.isPublishedByInstitute())
                .tagsCkb(new LinkedHashSet<>(safeSet(request.getTags()     != null ? request.getTags().getCkb()     : null)))
                .tagsKmr(new LinkedHashSet<>(safeSet(request.getTags()     != null ? request.getTags().getKmr()     : null)))
                .keywordsCkb(new LinkedHashSet<>(safeSet(request.getKeywords() != null ? request.getKeywords().getCkb() : null)))
                .keywordsKmr(new LinkedHashSet<>(safeSet(request.getKeywords() != null ? request.getKeywords().getKmr() : null)))
                .seriesId(seriesId)
                .seriesName(request.getSeriesName())
                .seriesOrder(seriesOrder)
                .parentBook(parentBook)
                .persons(new ArrayList<>())
                .build();

        applyPersons(writing, request.getPersons());
        applyContent(writing, request, ckbFileUrl, kmrFileUrl);

        mediaMetadata.safely(() -> mediaMetadata.applyToWriting(writing));
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
        if (request == null) request = new UpdateRequest();

        log.info("نوێکردنەوەی نووسراو id={}", id);

        Writing writing = findOrThrow(id, "writing.not_found");

        validate(request);

        // Resolve references before any S3 or Tiptap upload. A failed topic or
        // parent lookup must not leave an orphaned object in S3.
        Set<PublishmentTopic> resolvedTopics = null;
        boolean updatesTopics = !Boolean.TRUE.equals(request.getClearTopic())
                && (request.getTopicIds() != null || request.getNewTopics() != null
                    || request.getTopicId() != null || request.getNewTopic() != null);
        if (updatesTopics) {
            resolvedTopics = resolveTopics(
                    request.getTopicIds(), request.getNewTopics(),
                    request.getTopicId(), request.getNewTopic());
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
            writing.getTopics().clear();
        } else if (updatesTopics) {
            writing.getTopics().clear();
            writing.getTopics().addAll(resolvedTopics);
        }

        // ─── Book Genres ─────────────────────────────────────────────────────
        // genreIds preferred, legacy enum codes accepted; empty = leave unchanged.
        var resolvedGenres = bookGenreService.resolve(request.getGenreIds(), request.getBookGenres());
        if (!resolvedGenres.isEmpty()) {
            writing.getGenres().clear();
            writing.getGenres().addAll(resolvedGenres);
        }

        if (request.getPublishedByInstitute() != null) writing.setPublishedByInstitute(request.getPublishedByInstitute());

        if (request.getContentLanguages() != null && !request.getContentLanguages().isEmpty()) {
            writing.setContentLanguages(new LinkedHashSet<>(request.getContentLanguages()));
        }
        applyContent(writing, request, ckbFileUrl, kmrFileUrl);

        if (request.getPersons() != null) {
            writing.getPersons().clear();
            applyPersons(writing, request.getPersons());
        }

        replaceBilingualSets(writing, request);

        if (request.getBookLanguageCkb() != null) writing.setBookLanguageCkb(request.getBookLanguageCkb());
        if (request.getBookLanguageKmr() != null) writing.setBookLanguageKmr(request.getBookLanguageKmr());

        String oldSeriesId = writing.getSeriesId();
        if (request.getSeriesName()  != null) writing.setSeriesName(request.getSeriesName());
        if (request.getSeriesOrder() != null) writing.setSeriesOrder(request.getSeriesOrder());
        if (resolvedParent != null) {
            writing.setParentBook(resolvedParent);
            writing.setSeriesId(resolvedParent.getSeriesId());
        }

        mediaMetadata.safely(() -> mediaMetadata.applyToWriting(writing));
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

        // Remove every file this writing stored in S3
        List<String> urls = new ArrayList<>();
        urls.add(writing.getCkbCoverUrl());
        urls.add(writing.getKmrCoverUrl());
        urls.add(writing.getHoverCoverUrl());
        urls.add(writing.getFeatureImageUrl());
        if (writing.getCkbContent() != null) urls.add(writing.getCkbContent().getFileUrl());
        if (writing.getKmrContent() != null) urls.add(writing.getKmrContent().getFileUrl());
        s3Service.deleteFiles(urls);

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
        if (request == null) request = new LinkToSeriesRequest();
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

        String seriesName = books.stream()
                .map(Writing::getSeriesName)
                .filter(n -> n != null && !n.isBlank())
                .findFirst()
                .orElseGet(() -> books.get(0).getEffectiveSeriesName());
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
            return createInlineTopic(newTopic);
        }
        return null;
    }

    /**
     * Resolves every supplied topic reference — plural fields first, then the
     * legacy singular fields merged in — into an ordered de-duplicated set.
     */
    private Set<PublishmentTopic> resolveTopics(Set<Long> topicIds,
                                                List<TopicPayload> newTopics,
                                                Long legacyTopicId,
                                                TopicPayload legacyNewTopic) {
        Set<PublishmentTopic> out = new LinkedHashSet<>();
        if (topicIds != null) {
            topicIds.stream().filter(Objects::nonNull).distinct()
                    .forEach(id -> out.add(topicRepository.findById(id)
                            .orElseThrow(() -> Errors.notFound("topic.not_found", Map.of("topicId", id)))));
        }
        if (legacyTopicId != null) {
            out.add(topicRepository.findById(legacyTopicId)
                    .orElseThrow(() -> Errors.notFound("topic.not_found", Map.of("topicId", legacyTopicId))));
        }
        if (newTopics != null) {
            newTopics.stream().filter(Objects::nonNull)
                    .filter(n -> !isBlank(n.getNameCkb()) || !isBlank(n.getNameKmr()))
                    .forEach(n -> out.add(createInlineTopic(n)));
        }
        if (legacyNewTopic != null
                && (!isBlank(legacyNewTopic.getNameCkb()) || !isBlank(legacyNewTopic.getNameKmr()))) {
            out.add(createInlineTopic(legacyNewTopic));
        }
        return out;
    }

    private PublishmentTopic createInlineTopic(TopicPayload newTopic) {
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

    // All fields are optional — saves must never bounce on missing content.
    private void validate(CreateRequest request, boolean isCreate) {
    }

    private void validate(UpdateRequest request) {
    }

    // =========================================================================
    // ناوەڕۆک
    // =========================================================================

    private void applyContent(Writing writing, CreateRequest request, String ckbFileUrl, String kmrFileUrl) {
        var langs = request.getContentLanguages();
        if ((langs == null && request.getCkbContent() != null)
                || (langs != null && langs.contains(Language.CKB))) {
            writing.setCkbContent(buildContent(request.getCkbContent(), ckbFileUrl));
        }
        if ((langs == null && request.getKmrContent() != null)
                || (langs != null && langs.contains(Language.KMR))) {
            writing.setKmrContent(buildContent(request.getKmrContent(), kmrFileUrl));
        }
    }

    /** Rebuilds the person rows from a payload list; entries without any name are skipped. */
    private void applyPersons(Writing writing, List<PersonPayload> persons) {
        if (persons == null) return;
        int order = 0;
        for (PersonPayload p : persons) {
            if (p == null) continue;
            if (isBlank(p.getNameCkb()) && isBlank(p.getNameKmr())) continue;
            writing.getPersons().add(WritingPerson.builder()
                    .writing(writing)
                    .role(trimOrNull(p.getRole()))
                    .nameCkb(trimOrNull(p.getNameCkb()))
                    .nameKmr(trimOrNull(p.getNameKmr()))
                    .nicknameCkb(trimOrNull(p.getNicknameCkb()))
                    .nicknameKmr(trimOrNull(p.getNicknameKmr()))
                    .sortOrder(order++)
                    .build());
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
                .description(tiptapHtmlProcessor.process(trimOrNull(dto.getDescription()), "writings"))
                .writer(trimOrNull(dto.getWriter()))
                .fileUrl(fileUrl != null ? fileUrl : trimOrNull(dto.getFileUrl()))
                .fileFormat(dto.getFileFormat())
                .fileSizeBytes(dto.getFileSizeBytes())
                .pageCount(dto.getPageCount())
                .genre(trimOrNull(dto.getGenre()))
                .location(trimOrNull(dto.getLocation()))
                .build();
    }

    private WritingContent mergeContent(WritingContent existing, LanguageContentDto dto, String fileUrl) {
        if (existing == null) return buildContent(dto, fileUrl);
        if (dto.getTitle()         != null) existing.setTitle(trimOrNull(dto.getTitle()));
        if (dto.getDescription()   != null) existing.setDescription(tiptapHtmlProcessor.process(trimOrNull(dto.getDescription()), "writings"));
        if (dto.getWriter()        != null) existing.setWriter(trimOrNull(dto.getWriter()));
        // No new multipart file and no fileUrl in the DTO means retain the
        // persisted book source during metadata-only updates.
        if (fileUrl                != null) existing.setFileUrl(fileUrl);
        else if (dto.getFileUrl()  != null) existing.setFileUrl(trimOrNull(dto.getFileUrl()));
        if (dto.getFileFormat()    != null) existing.setFileFormat(dto.getFileFormat());
        if (dto.getFileSizeBytes() != null) existing.setFileSizeBytes(dto.getFileSizeBytes());
        if (dto.getPageCount()     != null) existing.setPageCount(dto.getPageCount());
        if (dto.getGenre()         != null) existing.setGenre(trimOrNull(dto.getGenre()));
        if (dto.getLocation()      != null) existing.setLocation(trimOrNull(dto.getLocation()));
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

    private TopicInfo primaryTopic(Writing w) {
        if (w.getTopics() == null || w.getTopics().isEmpty()) return null;
        PublishmentTopic t = w.getTopics().iterator().next();
        return toTopicInfo(t);
    }

    private TopicInfo toTopicInfo(PublishmentTopic t) {
        return TopicInfo.builder()
                .id(t.getId())
                .nameCkb(t.getNameCkb())
                .nameKmr(t.getNameKmr())
                .build();
    }

    private Response mapToResponse(Writing w) {
        Response r = Response.builder()
                .id(w.getId())
                .contentLanguages(w.getContentLanguages() != null
                        ? new LinkedHashSet<>(w.getContentLanguages()) : new LinkedHashSet<>())
                .ckbCoverUrl(w.getCkbCoverUrl())
                .kmrCoverUrl(w.getKmrCoverUrl())
                .hoverCoverUrl(w.getHoverCoverUrl())
                .featureImageUrl(w.getFeatureImageUrl())
                .topic(primaryTopic(w))
                .topics(w.getTopics() != null
                        ? w.getTopics().stream().map(this::toTopicInfo).toList()
                        : List.of())
                .bookGenres(w.getGenres() != null
                        ? w.getGenres().stream().map(g -> g.getSlug())
                                .collect(Collectors.toCollection(LinkedHashSet::new))
                        : new LinkedHashSet<>())
                .genres(w.getGenres() != null
                        ? w.getGenres().stream().map(g -> GenreInfo.builder()
                                .id(g.getId()).slug(g.getSlug())
                                .nameCkb(g.getNameCkb()).nameKmr(g.getNameKmr())
                                .build()).toList()
                        : List.of())
                .bookLanguageCkb(w.getBookLanguageCkb())
                .bookLanguageKmr(w.getBookLanguageKmr())
                .publishedByInstitute(w.isPublishedByInstitute())
                .persons(w.getPersons() != null
                        ? w.getPersons().stream().map(p -> PersonDto.builder()
                                .id(p.getId()).role(p.getRole())
                                .nameCkb(p.getNameCkb()).nameKmr(p.getNameKmr())
                                .nicknameCkb(p.getNicknameCkb()).nicknameKmr(p.getNicknameKmr())
                                .sortOrder(p.getSortOrder())
                                .build()).toList()
                        : List.of())
                .createdAt(w.getCreatedAt())
                .updatedAt(w.getUpdatedAt())
                .build();

        if (w.getCkbContent() != null) {
            WritingContent c = w.getCkbContent();
            r.setCkbContent(LanguageContentDto.builder()
                    .title(c.getTitle()).description(c.getDescription()).writer(c.getWriter())
                    .fileUrl(c.getFileUrl()).fileFormat(c.getFileFormat())
                    .fileSizeBytes(c.getFileSizeBytes()).pageCount(c.getPageCount())
                    .genre(c.getGenre()).location(c.getLocation())
                    .build());
        }

        if (w.getKmrContent() != null) {
            WritingContent c = w.getKmrContent();
            r.setKmrContent(LanguageContentDto.builder()
                    .title(c.getTitle()).description(c.getDescription()).writer(c.getWriter())
                    .fileUrl(c.getFileUrl()).fileFormat(c.getFileFormat())
                    .fileSizeBytes(c.getFileSizeBytes()).pageCount(c.getPageCount())
                    .genre(c.getGenre()).location(c.getLocation())
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
            String url = s3Service.upload(file.getBytes(), file.getOriginalFilename(), file.getContentType(),
                    s3Service.entityFolder("writings", file.getContentType()));
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
    private <T> Set<T> safeSet(Set<T> s)             { return s == null ? Set.of() : s; }
    private Set<String> cleanStrings(Set<String> in) {
        if (in == null || in.isEmpty()) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : in) { if (s != null && !s.isBlank()) out.add(s.trim()); }
        return out;
    }
}
