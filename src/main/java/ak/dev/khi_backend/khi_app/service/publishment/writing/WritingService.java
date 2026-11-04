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
            // Always resolve to the chain's ROOT — children never parent other
            // children, no matter which member the editor picked.
            parentBook  = resolveRoot(findOrThrow(request.getParentBookId(), "parent_book.not_found"));
            ensureSeriesId(parentBook);
            seriesId    = parentBook.getSeriesId();
            if (seriesOrder == null) {
                Double maxOrder = writingRepository.findMaxSeriesOrder(seriesId);
                seriesOrder = (maxOrder != null ? maxOrder : 0.0) + 1.0;
            }
        }
        boolean startSeries = Boolean.TRUE.equals(request.getStartSeries()) && parentBook == null;
        Integer plannedTotal = parentBook == null ? request.getSeriesTotalBooks() : null;

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
                .seriesOrder(seriesOrder)
                .parentBook(parentBook)
                .seriesPlannedTotal(plannedTotal)
                .persons(new ArrayList<>())
                .build();

        applyPersons(writing, request.getPersons());
        applyContent(writing, request, ckbFileUrl, kmrFileUrl);

        mediaMetadata.safely(() -> mediaMetadata.applyToWriting(writing));
        Writing saved = writingRepository.save(writing);

        if (startSeries && saved.getSeriesId() == null) {
            saved.setSeriesId("series-" + saved.getId());
        }
        refreshSeries(saved.getSeriesId());

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

        // 1) Explicit detach — a detached ROOT hands the chain to its next
        //    child, which keeps the seriesId and the declared total.
        if (Boolean.TRUE.equals(request.getUnlinkFromSeries())) {
            detachFromSeries(writing);
        }

        // 2) Reparent — resolved to the ROOT so membership stays flat. A moved
        //    root takes its whole chain along (the two series merge).
        if (resolvedParent != null) {
            Writing root = resolveRoot(resolvedParent);
            if (Objects.equals(root.getId(), writing.getId())) {
                throw Errors.badRequest("series.cyclic", Map.of("bookId", writing.getId()));
            }
            ensureSeriesId(root);
            adoptChildren(writing, root);
            writing.setParentBook(root);
            writing.setSeriesId(root.getSeriesId());
        }

        // 3) Declare a series primary — only a parentless book can be a root.
        if (Boolean.TRUE.equals(request.getStartSeries()) && writing.getParentBook() == null) {
            if (writing.getSeriesId() == null) {
                writing.setSeriesId("series-" + writing.getId());
            }
        }

        if (request.getSeriesOrder() != null && writing.getSeriesId() != null) {
            writing.setSeriesOrder(request.getSeriesOrder());
        }
        if (request.getSeriesTotalBooks() != null && writing.getParentBook() == null) {
            writing.setSeriesPlannedTotal(request.getSeriesTotalBooks());
        }

        mediaMetadata.safely(() -> mediaMetadata.applyToWriting(writing));
        Writing updated = writingRepository.save(writing);

        if (oldSeriesId != null && !oldSeriesId.equals(updated.getSeriesId())) {
            refreshSeries(oldSeriesId);
        }
        refreshSeries(updated.getSeriesId());

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
        // When the deleted book is the chain's ROOT, the next-in-line child is
        // promoted to primary — it keeps the seriesId and the declared total,
        // and the remaining children are repointed straight at it.
        List<Writing> childBooks = new ArrayList<>(writing.getSeriesBooks());
        childBooks.sort(Comparator
                .comparing((Writing c) -> c.getSeriesOrder() != null ? c.getSeriesOrder() : Double.MAX_VALUE)
                .thenComparing(c -> c.getCreatedAt() != null ? c.getCreatedAt() : LocalDateTime.MIN));
        if (!childBooks.isEmpty()) {
            Writing newRoot = childBooks.get(0);
            newRoot.setParentBook(null);
            newRoot.setSeriesPlannedTotal(writing.getSeriesPlannedTotal());
            for (int i = 1; i < childBooks.size(); i++) {
                childBooks.get(i).setParentBook(newRoot);
            }
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

        refreshSeries(seriesId);
    }

    // =========================================================================
    // خوێندنەوە
    // =========================================================================

    @Transactional(readOnly = true)
    public Page<Response> getAllWritings(Pageable pageable) {
        return writingRepository.findAllWithTopic(pageable).map(this::mapToResponse);
    }

    /**
     * Bulk list-order update (dashboard drag & drop).
     * orderedIds[i] gets sortOrder = i; unlisted ids keep their current value
     * and still render after the ordered ones (nulls last).
     */
    @Transactional
    public void reorder(List<Long> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) return;
        List<Writing> rows = writingRepository.findAllById(orderedIds);
        Map<Long, Integer> positions = new HashMap<>();
        for (int i = 0; i < orderedIds.size(); i++) {
            if (orderedIds.get(i) != null) positions.put(orderedIds.get(i), i);
        }
        for (Writing writing : rows) {
            Integer position = positions.get(writing.getId());
            if (position != null) writing.setSortOrder(position);
        }
        writingRepository.saveAll(rows);
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

        // Resolve to the chain's ROOT — linking against a child attaches the
        // book to that child's root, never to the child itself.
        Writing root = resolveRoot(parent);
        if (Objects.equals(root.getId(), book.getId())) {
            throw Errors.badRequest("series.cyclic", Map.of("bookId", book.getId()));
        }
        ensureSeriesId(root);

        String oldSeriesId = book.getSeriesId();
        adoptChildren(book, root);
        book.setParentBook(root);
        book.setSeriesId(root.getSeriesId());
        book.setSeriesOrder(request.getSeriesOrder());

        Writing updated = writingRepository.save(book);
        if (oldSeriesId != null && !oldSeriesId.equals(updated.getSeriesId())) {
            refreshSeries(oldSeriesId);
        }
        refreshSeries(updated.getSeriesId());
        logAction(updated, "LINKED_TO_SERIES", "لکێندراوە بە زنجیرە " + root.getSeriesId());
        return mapToResponse(updated);
    }

    @Transactional(readOnly = true)
    public SeriesResponse getSeriesBooks(String seriesId) {
        List<Writing> books = writingRepository.findBySeriesIdOrderBySeriesOrderAsc(seriesId);
        if (books.isEmpty()) {
            throw new NotFoundException("series.not_found", Map.of("seriesId", seriesId));
        }

        Writing root = books.stream()
                .filter(b -> b.getParentBook() == null)
                .findFirst()
                .orElse(books.get(0));

        String seriesName = books.stream()
                .map(Writing::getSeriesName)
                .filter(n -> n != null && !n.isBlank())
                .findFirst()
                .orElseGet(root::getEffectiveSeriesName);
        List<SeriesBookSummary> summaries = books.stream()
                .map(b -> SeriesBookSummary.builder()
                        .id(b.getId())
                        .titleCkb(b.getCkbContent() != null ? b.getCkbContent().getTitle() : null)
                        .titleKmr(b.getKmrContent() != null ? b.getKmrContent().getTitle() : null)
                        .seriesOrder(b.getSeriesOrder())
                        .ckbCoverUrl(b.getCkbCoverUrl())
                        .kmrCoverUrl(b.getKmrCoverUrl())
                        .hoverCoverUrl(b.getHoverCoverUrl())
                        .isParent(b.getParentBook() == null)
                        .createdAt(b.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        int memberCount = books.size();
        int effectiveTotal = Math.max(
                root.getSeriesPlannedTotal() != null ? root.getSeriesPlannedTotal() : 0,
                memberCount);

        return SeriesResponse.builder()
                .seriesId(seriesId)
                .seriesName(seriesName)
                .totalBooks(effectiveTotal)
                .memberCount(memberCount)
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

    /**
     * Walks a book's parent chain to the chain's ROOT (the primary first book).
     * Legacy data may nest children under other children; the hop limit keeps a
     * corrupt cycle from spinning forever.
     */
    private Writing resolveRoot(Writing book) {
        Writing current = book;
        int hops = 0;
        while (current.getParentBook() != null && hops++ < 25) {
            current = current.getParentBook();
        }
        return current;
    }

    /** A standalone book picked as a parent becomes the root of a new series. */
    private void ensureSeriesId(Writing root) {
        if (root.getSeriesId() == null) {
            root.setSeriesId("series-" + root.getId());
            writingRepository.save(root);
        }
    }

    /**
     * When a ROOT is itself linked under another root (series merge), its
     * children move along — they are repointed at the new root and rekeyed into
     * the new series.
     */
    private void adoptChildren(Writing from, Writing to) {
        if (from.getSeriesBooks() == null || from.getSeriesBooks().isEmpty()) return;
        List<Writing> children = new ArrayList<>(from.getSeriesBooks());
        for (Writing child : children) {
            child.setParentBook(to);
            child.setSeriesId(to.getSeriesId());
        }
        writingRepository.saveAll(children);
    }

    /**
     * Removes a book from its series. Detaching a ROOT promotes the lowest-
     * ordered child to primary — it inherits the seriesId and the declared
     * total, and the remaining children repoint at it.
     */
    private void detachFromSeries(Writing book) {
        if (book.getParentBook() == null
                && book.getSeriesBooks() != null
                && !book.getSeriesBooks().isEmpty()) {
            List<Writing> children = new ArrayList<>(book.getSeriesBooks());
            children.sort(Comparator
                    .comparing((Writing c) -> c.getSeriesOrder() != null ? c.getSeriesOrder() : Double.MAX_VALUE)
                    .thenComparing(c -> c.getCreatedAt() != null ? c.getCreatedAt() : LocalDateTime.MIN));
            Writing newRoot = children.get(0);
            newRoot.setParentBook(null);
            newRoot.setSeriesPlannedTotal(book.getSeriesPlannedTotal());
            for (int i = 1; i < children.size(); i++) {
                children.get(i).setParentBook(newRoot);
            }
            writingRepository.saveAll(children);
        }
        clearSeriesFields(book);
    }

    private void clearSeriesFields(Writing book) {
        book.setParentBook(null);
        book.setSeriesId(null);
        book.setSeriesName(null);
        book.setSeriesOrder(null);
        book.setSeriesTotalBooks(null);
        book.setSeriesPlannedTotal(null);
        book.setSeriesMemberCount(null);
    }

    // =========================================================================
    // ژماری زنجیرە
    // =========================================================================

    /**
     * Canonical series maintenance — one flat chain under a single root:
     *
     *   1. The parentless member with the lowest position is the ROOT. A series
     *      reduced to one undeclared book collapses back to a plain standalone
     *      record (no stray series key left behind).
     *   2. Every other member points straight at the root — legacy chains
     *      (A → B → C) and stray parent pointers flatten.
     *   3. Orders renumber contiguously: root = 1, children = 2..n keeping
     *      their relative declared order.
     *   4. The series name is re-derived from the root's title and the totals
     *      recomputed (effective = max(planned, members)) on every member.
     */
    @Transactional
    public void refreshSeries(String seriesId) {
        if (isBlank(seriesId)) return;
        List<Writing> books = writingRepository.findBySeriesIdOrderBySeriesOrderAsc(seriesId);
        if (books.isEmpty()) return;

        Writing root = books.stream()
                .filter(b -> b.getParentBook() == null)
                .findFirst()
                .orElse(books.get(0));

        int count = books.size();
        if (count == 1 && root.getSeriesPlannedTotal() == null) {
            clearSeriesFields(root);
            writingRepository.save(root);
            return;
        }

        String seriesName;
        if (root.getCkbContent() != null && !isBlank(root.getCkbContent().getTitle())) {
            seriesName = root.getCkbContent().getTitle();
        } else if (root.getKmrContent() != null && !isBlank(root.getKmrContent().getTitle())) {
            seriesName = root.getKmrContent().getTitle();
        } else {
            seriesName = root.getEffectiveSeriesName();
        }

        int effectiveTotal = Math.max(
                root.getSeriesPlannedTotal() != null ? root.getSeriesPlannedTotal() : 0,
                count);

        List<Writing> children = books.stream()
                .filter(b -> b != root)
                .sorted(Comparator
                        .comparing((Writing b) -> b.getSeriesOrder() != null ? b.getSeriesOrder() : Double.MAX_VALUE)
                        .thenComparing(b -> b.getCreatedAt() != null ? b.getCreatedAt() : LocalDateTime.MIN)
                        .thenComparing(Writing::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());

        applySeriesFields(root, null, 1.0, seriesName, effectiveTotal, count);
        double order = 1.0;
        for (Writing child : children) {
            order += 1.0;
            applySeriesFields(child, root, order, seriesName, effectiveTotal, count);
        }
        writingRepository.saveAll(books);
    }

    private void applySeriesFields(Writing book, Writing parent, double order,
                                   String seriesName, int effectiveTotal, int memberCount) {
        book.setParentBook(parent);
        book.setSeriesOrder(order);
        book.setSeriesName(seriesName);
        book.setSeriesTotalBooks(effectiveTotal);
        book.setSeriesMemberCount(memberCount);
        if (parent != null) {
            book.setSeriesPlannedTotal(null);
        }
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
                    .roleCkb(trimOrNull(p.getRoleCkb()))
                    .roleKmr(trimOrNull(p.getRoleKmr()))
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
                .sortOrder(w.getSortOrder())
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
                                .id(p.getId())
                                .roleCkb(p.getRoleCkb()).roleKmr(p.getRoleKmr())
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
                    .title(c.getTitle()).description(c.getDescription())
                    .fileUrl(c.getFileUrl()).fileFormat(c.getFileFormat())
                    .fileSizeBytes(c.getFileSizeBytes()).pageCount(c.getPageCount())
                    .genre(c.getGenre()).location(c.getLocation())
                    .build());
        }

        if (w.getKmrContent() != null) {
            WritingContent c = w.getKmrContent();
            r.setKmrContent(LanguageContentDto.builder()
                    .title(c.getTitle()).description(c.getDescription())
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
                    .plannedTotal(w.getSeriesPlannedTotal())
                    .memberCount(w.getSeriesMemberCount())
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
