package ak.dev.khi_backend.khi_app.service.news;

import ak.dev.khi_backend.khi_app.dto.news.NewsDto;
import ak.dev.khi_backend.khi_app.enums.Language;
import ak.dev.khi_backend.khi_app.enums.MediaKind;
import ak.dev.khi_backend.khi_app.exceptions.BadRequestException;
import ak.dev.khi_backend.khi_app.exceptions.Errors;
import ak.dev.khi_backend.khi_app.model.media.MediaItem;
import ak.dev.khi_backend.khi_app.model.news.*;
import ak.dev.khi_backend.khi_app.repository.news.NewsAuditLogRepository;
import ak.dev.khi_backend.khi_app.repository.news.NewsCategoryRepository;
import ak.dev.khi_backend.khi_app.repository.news.NewsRepository;
import ak.dev.khi_backend.khi_app.repository.news.NewsSubCategoryRepository;
import ak.dev.khi_backend.khi_app.service.media.TiptapHtmlProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * NewsService — Tiptap-aware News business logic.
 *
 * Inline media is no longer handled here. The frontend uploads inline
 * images / audio / video through the shared {@code POST /api/v1/media/upload}
 * endpoint and bakes the returned URLs into the Tiptap HTML before sending
 * the JSON body to {@link ak.dev.khi_backend.khi_app.api.news.NewsController}.
 *
 * Cover image: same flow — frontend uploads first, then passes the
 * {@code coverUrl} in the JSON.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsService {

    private final NewsRepository            newsRepository;
    private final NewsCategoryRepository    newsCategoryRepository;
    private final NewsSubCategoryRepository newsSubCategoryRepository;
    private final NewsAuditLogRepository    newsAuditLogRepository;
    private final TransactionTemplate       transactionTemplate;
    private final TiptapHtmlProcessor       tiptapHtmlProcessor;


    // ============================================================
    // CREATE
    // ============================================================

    @CacheEvict(value = "news", allEntries = true)
    public NewsDto addNews(NewsDto dto) {
        String traceId = traceId();
        log.info("Create news | traceId={}", traceId);

        validate(dto, true);

        News saved = transactionTemplate.execute(status -> {
            NewsCategory    cat    = getOrCreateCategory(dto.getCategory());
            NewsSubCategory subCat = getOrCreateSubCategory(dto.getSubCategory(), cat);

            News news = buildNewsEntity(dto, dto.getCoverUrl().trim(), cat, subCat);
            applyContentByLanguages(news, dto);

            News persisted = newsRepository.save(news);
            createAuditLog(persisted, "CREATE", "News created");
            return persisted;
        });

        return toDto(saved);
    }

    // ============================================================
    // CREATE BULK
    // ============================================================

    @CacheEvict(value = "news", allEntries = true)
    public List<NewsDto> addNewsBulk(List<NewsDto> list) {
        if (list == null || list.isEmpty()) {
            throw Errors.newsValidation("error.validation",
                    Map.of("field", "list", "message", "News list is empty"));
        }
        for (NewsDto dto : list) {
            validate(dto, true);
        }

        List<News> saved = transactionTemplate.execute(status -> {
            List<News> entities = new ArrayList<>(list.size());
            for (NewsDto dto : list) {
                NewsCategory    cat    = getOrCreateCategory(dto.getCategory());
                NewsSubCategory subCat = getOrCreateSubCategory(dto.getSubCategory(), cat);
                News news = buildNewsEntity(dto, dto.getCoverUrl().trim(), cat, subCat);
                applyContentByLanguages(news, dto);
                entities.add(news);
            }
            List<News> out = newsRepository.saveAll(entities);
            newsAuditLogRepository.saveAll(
                    out.stream()
                            .map(n -> buildAuditLog(n, "CREATE", "News bulk created"))
                            .toList()
            );
            return out;
        });

        return saved.stream().map(this::toDto).toList();
    }

    // ============================================================
    // READ
    // ============================================================

    @Cacheable(value = "news", key = "'all:p' + #page + ':s' + #size")
    @Transactional(readOnly = true)
    public Page<NewsDto> getAllNews(int page, int size) {
        Page<Long> idPage = newsRepository.findAllIds(PageRequest.of(page, size));

        if (idPage.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(),
                    idPage.getPageable(), idPage.getTotalElements());
        }

        List<News> hydrated = hydrateAndSort(idPage.getContent());

        return new PageImpl<>(
                hydrated.stream().map(this::toDto).collect(Collectors.toList()),
                idPage.getPageable(),
                idPage.getTotalElements()
        );
    }

    @Transactional(readOnly = true)
    public Page<NewsDto> getFeatured(int page, int size) {
        Pageable pageable = featuredPageable(page, size);
        return newsRepository.findByFeaturedTrue(pageable).map(this::toDto);
    }

    @Cacheable(value = "news",
            key = "'tag:' + #tag.toLowerCase() + ':lang:' + #language + ':p' + #page + ':s' + #size")
    @Transactional(readOnly = true)
    public Page<NewsDto> searchByTag(String tag, String language, int page, int size) {
        if (isBlank(tag)) {
            throw new BadRequestException("tag.required",
                    Map.of("message", "Search tag is required"));
        }

        Pageable pageable = PageRequest.of(page, size);
        String   t        = tag.trim();

        Page<Long> idPage;
        if ("ckb".equalsIgnoreCase(language)) {
            idPage = newsRepository.findIdsByTagCkb(t, pageable);
        } else if ("kmr".equalsIgnoreCase(language)) {
            idPage = newsRepository.findIdsByTagKmr(t, pageable);
        } else {
            idPage = newsRepository.findIdsByTag(t, pageable);
        }

        return mapPage(idPage);
    }

    @Cacheable(value = "news",
            key = "'kw:' + #keyword.toLowerCase() + ':lang:' + #language + ':p' + #page + ':s' + #size")
    @Transactional(readOnly = true)
    public Page<NewsDto> searchByKeyword(String keyword, String language, int page, int size) {
        if (isBlank(keyword)) {
            throw new BadRequestException("keyword.required",
                    Map.of("message", "Search keyword is required"));
        }

        Pageable pageable = PageRequest.of(page, size);
        String   kw       = keyword.trim();

        Page<Long> idPage;
        if ("ckb".equalsIgnoreCase(language)) {
            idPage = newsRepository.findIdsByKeywordCkb(kw, pageable);
        } else if ("kmr".equalsIgnoreCase(language)) {
            idPage = newsRepository.findIdsByKeywordKmr(kw, pageable);
        } else {
            idPage = newsRepository.findIdsByKeyword(kw, pageable);
        }

        return mapPage(idPage);
    }

    @Cacheable(value = "news",
            key = "'search:' + #q.toLowerCase() + ':p' + #page + ':s' + #size")
    @Transactional(readOnly = true)
    public Page<NewsDto> globalSearch(String q, int page, int size) {
        if (isBlank(q)) {
            throw new BadRequestException("keyword.required",
                    Map.of("message", "Search keyword is required"));
        }

        Page<Long> idPage = newsRepository.findIdsByGlobalSearch(
                q.trim(), PageRequest.of(page, size));

        return mapPage(idPage);
    }

    @Cacheable(value = "news",
            key = "'cat:' + #name.toLowerCase() + ':p' + #page + ':s' + #size")
    @Transactional(readOnly = true)
    public Page<NewsDto> searchByCategory(String name, int page, int size) {
        if (isBlank(name)) {
            throw new BadRequestException("news.category.required",
                    Map.of("field", "category"));
        }

        Page<Long> idPage = newsRepository.findIdsByCategory(
                name.trim(), PageRequest.of(page, size));

        return mapPage(idPage);
    }

    @Cacheable(value = "news",
            key = "'subcat:' + #name.toLowerCase() + ':p' + #page + ':s' + #size")
    @Transactional(readOnly = true)
    public Page<NewsDto> searchBySubCategory(String name, int page, int size) {
        if (isBlank(name)) {
            throw new BadRequestException("news.subcategory.required",
                    Map.of("field", "subCategory"));
        }

        Page<Long> idPage = newsRepository.findIdsBySubCategory(
                name.trim(), PageRequest.of(page, size));

        return mapPage(idPage);
    }

    private Page<NewsDto> mapPage(Page<Long> idPage) {
        if (idPage.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(),
                    idPage.getPageable(), idPage.getTotalElements());
        }
        List<News> hydrated = hydrateAndSort(idPage.getContent());
        return new PageImpl<>(
                hydrated.stream().map(this::toDto).collect(Collectors.toList()),
                idPage.getPageable(),
                idPage.getTotalElements()
        );
    }

    @Transactional(readOnly = true)
    public NewsDto getNewsById(Long id) {
        News news = newsRepository.findByIdWithGraph(id)
                .orElseThrow(() -> Errors.newsNotFound(id));
        return toDto(news);
    }

    // ============================================================
    // UPDATE
    // ============================================================

    @CacheEvict(value = "news", allEntries = true)
    public NewsDto updateNews(Long newsId, NewsDto dto) {
        String traceId = traceId();
        log.info("Update news | id={} | traceId={}", newsId, traceId);

        if (newsId == null) {
            throw new BadRequestException("error.validation",
                    Map.of("field", "id", "message", "News id is required"));
        }

        validate(dto, false);

        News updated = transactionTemplate.execute(status -> {
            News news = newsRepository.findByIdWithGraph(newsId)
                    .orElseThrow(() -> Errors.newsNotFound(newsId));

            if (!isBlank(dto.getCoverUrl())) {
                news.setCoverUrl(dto.getCoverUrl().trim());
            } else if (isBlank(news.getCoverUrl())) {
                throw Errors.newsValidation("news.cover.required",
                        Map.of("field", "coverUrl"));
            }
            if (dto.getCoverMediaType() != null) {
                news.setCoverMediaType(dto.getCoverMediaType());
            }
            if (dto.getCoverThumbnailUrl() != null) {
                news.setCoverThumbnailUrl(trimOrNull(dto.getCoverThumbnailUrl()));
            }
            if (dto.getMediaGallery() != null) {
                news.setMediaGallery(buildGallery(dto.getMediaGallery()));
            }

            if (dto.getDatePublished() != null) {
                news.setDatePublished(dto.getDatePublished());
            }

            if (dto.getCategory() != null) {
                news.setCategory(getOrCreateCategory(dto.getCategory()));
            }
            if (dto.getSubCategory() != null) {
                news.setSubCategory(
                        getOrCreateSubCategory(dto.getSubCategory(), news.getCategory()));
            }

            news.setContentLanguages(new LinkedHashSet<>(safeLangs(dto.getContentLanguages())));
            applyContentByLanguages(news, dto);
            replaceBilingualSets(news, dto);

            News persisted = newsRepository.save(news);
            createAuditLog(persisted, "UPDATE", "News updated");
            return persisted;
        });

        return toDto(updated);
    }

    // ============================================================
    // DELETE
    // ============================================================

    @CacheEvict(value = "news", allEntries = true)
    public void deleteNews(Long newsId) {
        if (newsId == null) return;

        transactionTemplate.executeWithoutResult(status -> {
            News news = newsRepository.findByIdWithGraph(newsId).orElse(null);
            if (news == null) {
                log.debug("News delete ignored; id={} does not exist", newsId);
                return;
            }
            createAuditLog(news, "DELETE", "News deleted");
            newsRepository.delete(news);
        });
    }

    @CacheEvict(value = "news", allEntries = true)
    public void deleteNewsBulk(List<Long> newsIds) {
        if (newsIds == null || newsIds.isEmpty()) return;

        transactionTemplate.executeWithoutResult(status -> {
            List<News> list = newsRepository.findAllById(newsIds);
            if (list.isEmpty()) return;
            newsAuditLogRepository.saveAll(
                    list.stream()
                            .map(n -> buildAuditLog(n, "DELETE", "News bulk deleted"))
                            .toList()
            );
            newsRepository.deleteAll(list);
        });
    }

    // ============================================================
    // PRIVATE — hydration / mapping
    // ============================================================

    private List<News> hydrateAndSort(List<Long> ids) {
        List<News> rows = newsRepository.findAllByIds(ids);

        Map<Long, News> indexed = new LinkedHashMap<>(rows.size());
        for (News n : rows) indexed.put(n.getId(), n);

        List<News> ordered = new ArrayList<>(ids.size());
        for (Long id : ids) {
            News n = indexed.get(id);
            if (n != null) ordered.add(n);
        }
        return ordered;
    }

    private News buildNewsEntity(NewsDto dto, String coverUrl,
                                 NewsCategory cat, NewsSubCategory subCat) {
        return News.builder()
                .coverUrl(coverUrl)
                .coverMediaType(dto.getCoverMediaType() != null
                        ? dto.getCoverMediaType() : MediaKind.IMAGE)
                .coverThumbnailUrl(trimOrNull(dto.getCoverThumbnailUrl()))
                .mediaGallery(buildGallery(dto.getMediaGallery()))
                .datePublished(dto.getDatePublished())
                .category(cat)
                .subCategory(subCat)
                .contentLanguages(new LinkedHashSet<>(safeLangs(dto.getContentLanguages())))
                .tagsCkb(new LinkedHashSet<>(cleanStrings(
                        dto.getTags() != null ? dto.getTags().getCkb() : null)))
                .tagsKmr(new LinkedHashSet<>(cleanStrings(
                        dto.getTags() != null ? dto.getTags().getKmr() : null)))
                .keywordsCkb(new LinkedHashSet<>(cleanStrings(
                        dto.getKeywords() != null ? dto.getKeywords().getCkb() : null)))
                .keywordsKmr(new LinkedHashSet<>(cleanStrings(
                        dto.getKeywords() != null ? dto.getKeywords().getKmr() : null)))
                .build();
    }

    private void validate(NewsDto dto, boolean createRequiresCover) {
        if (dto == null) {
            throw Errors.newsValidation("error.validation",
                    Map.of("field", "body", "message", "Request body is required"));
        }

        Set<Language> langs = safeLangs(dto.getContentLanguages());
        if (langs.isEmpty()) {
            throw Errors.newsValidation("news.languages.required",
                    Map.of("field", "contentLanguages"));
        }

        if (createRequiresCover && isBlank(dto.getCoverUrl())) {
            throw Errors.newsValidation("news.cover.required",
                    Map.of("field", "coverUrl"));
        }

        if (dto.getCategory() == null
                || isBlank(dto.getCategory().getCkbName())
                || isBlank(dto.getCategory().getKmrName())) {
            throw Errors.newsValidation("news.category.required",
                    Map.of("field", "category"));
        }
        if (dto.getSubCategory() == null
                || isBlank(dto.getSubCategory().getCkbName())
                || isBlank(dto.getSubCategory().getKmrName())) {
            throw Errors.newsValidation("news.subcategory.required",
                    Map.of("field", "subCategory"));
        }

        if (langs.contains(Language.CKB)) {
            if (dto.getCkbContent() == null || isBlank(dto.getCkbContent().getTitle())) {
                throw Errors.newsValidation("news.ckb.title.required",
                        Map.of("field", "ckbContent.title"));
            }
        }
        if (langs.contains(Language.KMR)) {
            if (dto.getKmrContent() == null || isBlank(dto.getKmrContent().getTitle())) {
                throw Errors.newsValidation("news.kmr.title.required",
                        Map.of("field", "kmrContent.title"));
            }
        }
    }

    private NewsCategory getOrCreateCategory(NewsDto.CategoryDto categoryDto) {
        String ckb = trimOrNull(categoryDto != null ? categoryDto.getCkbName() : null);
        String kmr = trimOrNull(categoryDto != null ? categoryDto.getKmrName() : null);

        if (isBlank(ckb) || isBlank(kmr)) {
            throw new BadRequestException("news.category.required",
                    Map.of("field", "category"));
        }

        NewsCategory existing = newsCategoryRepository.findByNameCkb(ckb).orElse(null);
        if (existing != null) {
            if (!kmr.equals(existing.getNameKmr())) {
                existing.setNameKmr(kmr);
                existing = newsCategoryRepository.save(existing);
            }
            return existing;
        }

        return newsCategoryRepository.save(
                NewsCategory.builder().nameCkb(ckb).nameKmr(kmr).build());
    }

    private NewsSubCategory getOrCreateSubCategory(NewsDto.SubCategoryDto subDto,
                                                   NewsCategory category) {
        String ckb = trimOrNull(subDto != null ? subDto.getCkbName() : null);
        String kmr = trimOrNull(subDto != null ? subDto.getKmrName() : null);

        if (isBlank(ckb) || isBlank(kmr)) {
            throw new BadRequestException("news.subcategory.required",
                    Map.of("field", "subCategory"));
        }

        NewsSubCategory existing =
                newsSubCategoryRepository.findByCategoryAndNameCkb(category, ckb).orElse(null);
        if (existing != null) {
            if (!kmr.equals(existing.getNameKmr())) {
                existing.setNameKmr(kmr);
                existing = newsSubCategoryRepository.save(existing);
            }
            return existing;
        }

        return newsSubCategoryRepository.save(
                NewsSubCategory.builder()
                        .nameCkb(ckb).nameKmr(kmr).category(category)
                        .build());
    }

    private void applyContentByLanguages(News news, NewsDto dto) {
        Set<Language> langs = safeLangs(news.getContentLanguages());

        if (langs.contains(Language.CKB)) {
            news.setCkbContent(buildContent(dto.getCkbContent()));
        } else {
            news.setCkbContent(null);
            news.getTagsCkb().clear();
            news.getKeywordsCkb().clear();
        }

        if (langs.contains(Language.KMR)) {
            news.setKmrContent(buildContent(dto.getKmrContent()));
        } else {
            news.setKmrContent(null);
            news.getTagsKmr().clear();
            news.getKeywordsKmr().clear();
        }
    }

    private NewsContent buildContent(NewsDto.LanguageContentDto dto) {
        if (dto == null) return null;
        if (isBlank(dto.getTitle()) && isBlank(dto.getDescription())) return null;
        return NewsContent.builder()
                .title(trimOrNull(dto.getTitle()))
                .description(tiptapHtmlProcessor.process(dto.getDescription()))
                .build();
    }

    private void replaceBilingualSets(News news, NewsDto dto) {
        if (dto.getTags() != null) {
            if (dto.getTags().getCkb() != null) {
                news.getTagsCkb().clear();
                news.getTagsCkb().addAll(cleanStrings(dto.getTags().getCkb()));
            }
            if (dto.getTags().getKmr() != null) {
                news.getTagsKmr().clear();
                news.getTagsKmr().addAll(cleanStrings(dto.getTags().getKmr()));
            }
        }
        if (dto.getKeywords() != null) {
            if (dto.getKeywords().getCkb() != null) {
                news.getKeywordsCkb().clear();
                news.getKeywordsCkb().addAll(cleanStrings(dto.getKeywords().getCkb()));
            }
            if (dto.getKeywords().getKmr() != null) {
                news.getKeywordsKmr().clear();
                news.getKeywordsKmr().addAll(cleanStrings(dto.getKeywords().getKmr()));
            }
        }
    }

    private NewsDto toDto(News news) {
        NewsDto dto = NewsDto.builder()
                .id(news.getId())
                .coverUrl(news.getCoverUrl())
                .coverMediaType(news.getCoverMediaType() != null
                        ? news.getCoverMediaType() : MediaKind.IMAGE)
                .coverThumbnailUrl(news.getCoverThumbnailUrl())
                .featureImageUrl(news.getFeatureImageUrl())
                .mediaGallery(news.getMediaGallery() != null
                        ? new ArrayList<>(news.getMediaGallery())
                        : new ArrayList<>())
                .datePublished(news.getDatePublished())
                .createdAt(news.getCreatedAt())
                .updatedAt(news.getUpdatedAt())
                .contentLanguages(news.getContentLanguages() != null
                        ? new LinkedHashSet<>(news.getContentLanguages())
                        : new LinkedHashSet<>())
                .build();

        if (news.getCategory() != null) {
            dto.setCategory(NewsDto.CategoryDto.builder()
                    .ckbName(news.getCategory().getNameCkb())
                    .kmrName(news.getCategory().getNameKmr())
                    .build());
        }

        if (news.getSubCategory() != null) {
            dto.setSubCategory(NewsDto.SubCategoryDto.builder()
                    .ckbName(news.getSubCategory().getNameCkb())
                    .kmrName(news.getSubCategory().getNameKmr())
                    .build());
        }

        if (news.getCkbContent() != null) {
            dto.setCkbContent(NewsDto.LanguageContentDto.builder()
                    .title(news.getCkbContent().getTitle())
                    .description(news.getCkbContent().getDescription())
                    .build());
        }

        if (news.getKmrContent() != null) {
            dto.setKmrContent(NewsDto.LanguageContentDto.builder()
                    .title(news.getKmrContent().getTitle())
                    .description(news.getKmrContent().getDescription())
                    .build());
        }

        dto.setTags(NewsDto.BilingualSet.builder()
                .ckb(new LinkedHashSet<>(safeSet(news.getTagsCkb())))
                .kmr(new LinkedHashSet<>(safeSet(news.getTagsKmr())))
                .build());

        dto.setKeywords(NewsDto.BilingualSet.builder()
                .ckb(new LinkedHashSet<>(safeSet(news.getKeywordsCkb())))
                .kmr(new LinkedHashSet<>(safeSet(news.getKeywordsKmr())))
                .build());

        return dto;
    }

    private Pageable featuredPageable(int page, int size) {
        return PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.ASC, "featuredOrder")
                        .and(Sort.by(Sort.Direction.DESC, "id"))
        );
    }

    private void createAuditLog(News news, String action, String note) {
        newsAuditLogRepository.save(buildAuditLog(news, action, note));
    }

    private NewsAuditLog buildAuditLog(News news, String action, String note) {
        return NewsAuditLog.builder()
                .newsId(news.getId())
                .action(action)
                .performedBy("system")
                .note(note)
                .build();
    }

    // ============================================================
    // utilities
    // ============================================================

    private boolean isBlank(String s)                { return s == null || s.isBlank(); }
    private String  trimOrNull(String s)             { if (s == null) return null; String t = s.trim(); return t.isEmpty() ? null : t; }
    private Set<Language>  safeLangs(Set<Language> l){ return l == null ? Set.of() : l; }
    private <T> Set<T>     safeSet(Set<T> s)         { return s == null ? Set.of() : s; }

    private List<MediaItem> buildGallery(List<MediaItem> gallery) {
        if (gallery == null || gallery.isEmpty()) return new ArrayList<>();
        ArrayList<MediaItem> result = new ArrayList<>();
        int idx = 0;
        for (MediaItem item : gallery) {
            if (item == null || isBlank(item.getUrl())) continue;
            result.add(MediaItem.builder()
                    .url(item.getUrl().trim())
                    .kind(item.getKind() != null ? item.getKind() : MediaKind.IMAGE)
                    .thumbnailUrl(trimOrNull(item.getThumbnailUrl()))
                    .captionCkb(trimOrNull(item.getCaptionCkb()))
                    .captionKmr(trimOrNull(item.getCaptionKmr()))
                    .sortOrder(item.getSortOrder() != null ? item.getSortOrder() : idx)
                    .build());
            idx++;
        }
        result.sort(Comparator.comparingInt(
                m -> m.getSortOrder() != null ? m.getSortOrder() : Integer.MAX_VALUE));
        return result;
    }

    private Set<String> cleanStrings(Set<String> input) {
        if (input == null || input.isEmpty()) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : input) { if (s != null && !s.isBlank()) out.add(s.trim()); }
        return out;
    }

    private String traceId() {
        String t = MDC.get("traceId");
        return (t != null && !t.isBlank()) ? t : UUID.randomUUID().toString();
    }


}
