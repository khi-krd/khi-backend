package ak.dev.khi_backend.khi_app.service.search;


import ak.dev.khi_backend.khi_app.dto.search.GlobalSearchResponse;
import ak.dev.khi_backend.khi_app.dto.search.GlobalSearchResponse.SearchSection;
import ak.dev.khi_backend.khi_app.dto.search.SearchItem;
import ak.dev.khi_backend.khi_app.model.news.News;
import ak.dev.khi_backend.khi_app.model.project.Project;
import ak.dev.khi_backend.khi_app.model.publishment.image.ImageCollection;
import ak.dev.khi_backend.khi_app.model.publishment.sound.SoundTrack;
import ak.dev.khi_backend.khi_app.model.publishment.video.Video;
import ak.dev.khi_backend.khi_app.model.publishment.writing.Writing;
import ak.dev.khi_backend.khi_app.repository.news.NewsRepository;
import ak.dev.khi_backend.khi_app.repository.project.ProjectRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.image.ImageCollectionRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.sound.SoundTrackRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.video.VideoRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.writing.WritingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * GlobalSearchService
 *
 * Single service that searches across all 6 content models.
 *
 * ─── Strategy ─────────────────────────────────────────────────────────────────
 *
 *  Every model uses the same two-phase approach:
 *
 *  Phase 1 — ID query (lightweight, paginated, index-friendly)
 *    repo.findIdsByGlobalSearch(q, pageable) → Page<Long>
 *    Hits only the primary columns (titles, descriptions) + collection
 *    tables (tags, keywords) with DISTINCT. No entity hydration.
 *
 *  Phase 2 — Batch hydration (bare entities, no collection joins)
 *    repo.findAllByIds(idPage.getContent()) → List<Entity>
 *    Loads only the scalar columns. Hibernate's @BatchSize annotations
 *    on each entity's collections are NOT triggered here because we
 *    never touch those collections — SearchItem only needs id, titles,
 *    description, and cover URL.
 *
 *  Result: for a page of 10 items per type × 6 types = 12 fast queries
 *  (2 per type), regardless of how many tags/keywords each item has.
 *
 * ─── type filter ──────────────────────────────────────────────────────────────
 *
 *  type = ALL       → all 6 sections populated (default)
 *  type = PROJECT   → only projects section
 *  type = NEWS      → only news section
 *  type = VIDEO     → only videos section
 *  type = WRITING   → only writings section
 *  type = SOUNDTRACK→ only soundTracks section
 *  type = IMAGE     → only imageCollections section
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GlobalSearchService {

    private final ProjectRepository         projectRepo;
    private final NewsRepository            newsRepo;
    private final VideoRepository           videoRepo;
    private final WritingRepository         writingRepo;
    private final SoundTrackRepository      soundTrackRepo;
    private final ImageCollectionRepository imageCollectionRepo;

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Run global search across all (or one) content type(s).
     *
     * @param q    search term — empty string returns all items
     * @param type content scope: ALL | PROJECT | NEWS | VIDEO | WRITING | SOUNDTRACK | IMAGE
     * @param page 0-based page index
     * @param size items per page per section
     */
    public GlobalSearchResponse search(String q, String type, int page, int size) {
        String query     = q    == null ? "" : q.trim();
        String typeUpper = type == null ? "ALL" : type.trim().toUpperCase();

        Pageable pageable = PageRequest.of(page, size);

        log.debug("GlobalSearch | q='{}' type={} page={} size={}", query, typeUpper, page, size);

        GlobalSearchResponse.GlobalSearchResponseBuilder builder = GlobalSearchResponse.builder()
                .query(query)
                .page(page)
                .size(size)
                .type(typeUpper);

        if (searchesType(typeUpper, "PROJECT"))    builder.projects(searchProjects(query, pageable));
        if (searchesType(typeUpper, "NEWS"))       builder.news(searchNews(query, pageable));
        if (searchesType(typeUpper, "VIDEO"))      builder.videos(searchVideos(query, pageable));
        if (searchesType(typeUpper, "WRITING"))    builder.writings(searchWritings(query, pageable));
        if (searchesType(typeUpper, "SOUNDTRACK")) builder.soundTracks(searchSoundTracks(query, pageable));
        if (searchesType(typeUpper, "IMAGE"))      builder.imageCollections(searchImageCollections(query, pageable));

        return builder.build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE — per-model search methods
    // ─────────────────────────────────────────────────────────────────────────

    // ── Projects ──────────────────────────────────────────────────────────────

    private SearchSection searchProjects(String q, Pageable pageable) {
        Page<Long> idPage = projectRepo.findIdsByGlobalSearch(q, pageable);
        if (idPage.isEmpty()) return SearchSection.empty(pageable.getPageNumber(), pageable.getPageSize());

        // Phase 2: load bare entities (order preserved below)
        Map<Long, Project> byId = projectRepo.findAllByIds(idPage.getContent())
                .stream()
                .collect(Collectors.toMap(Project::getId, Function.identity()));

        List<SearchItem> items = idPage.getContent().stream()
                .map(id -> {
                    Project p = byId.get(id);
                    if (p == null) return null;
                    return SearchItem.builder()
                            .id(p.getId())
                            .type("PROJECT")
                            .titleCkb(title(p.getCkbContent() != null ? p.getCkbContent().getTitle() : null))
                            .titleKmr(title(p.getKmrContent() != null ? p.getKmrContent().getTitle() : null))
                            .descriptionCkb(snippet(p.getCkbContent() != null ? p.getCkbContent().getDescription() : null))
                            .descriptionKmr(snippet(p.getKmrContent() != null ? p.getKmrContent().getDescription() : null))
                            .coverUrl(p.getCoverUrl())
                            .createdAt(p.getCreatedAt())
                            .build();
                })
                .filter(i -> i != null)
                .collect(Collectors.toList());

        return toSection(items, idPage, pageable);
    }

    // ── News ──────────────────────────────────────────────────────────────────

    private SearchSection searchNews(String q, Pageable pageable) {
        Page<Long> idPage = newsRepo.findIdsByGlobalSearch(q, pageable);
        if (idPage.isEmpty()) return SearchSection.empty(pageable.getPageNumber(), pageable.getPageSize());

        Map<Long, News> byId = newsRepo.findAllByIds(idPage.getContent())
                .stream()
                .collect(Collectors.toMap(News::getId, Function.identity()));

        List<SearchItem> items = idPage.getContent().stream()
                .map(id -> {
                    News n = byId.get(id);
                    if (n == null) return null;
                    return SearchItem.builder()
                            .id(n.getId())
                            .type("NEWS")
                            .titleCkb(title(n.getCkbContent() != null ? n.getCkbContent().getTitle() : null))
                            .titleKmr(title(n.getKmrContent() != null ? n.getKmrContent().getTitle() : null))
                            .descriptionCkb(snippet(n.getCkbContent() != null ? n.getCkbContent().getDescription() : null))
                            .descriptionKmr(snippet(n.getKmrContent() != null ? n.getKmrContent().getDescription() : null))
                            .coverUrl(n.getCoverUrl())
                            .createdAt(n.getCreatedAt())
                            .build();
                })
                .filter(i -> i != null)
                .collect(Collectors.toList());

        return toSection(items, idPage, pageable);
    }

    // ── Videos ────────────────────────────────────────────────────────────────

    private SearchSection searchVideos(String q, Pageable pageable) {
        Page<Long> idPage = videoRepo.findIdsByGlobalSearch(q, pageable);
        if (idPage.isEmpty()) return SearchSection.empty(pageable.getPageNumber(), pageable.getPageSize());

        Map<Long, Video> byId = videoRepo.findAllByIds(idPage.getContent())
                .stream()
                .collect(Collectors.toMap(Video::getId, Function.identity()));

        List<SearchItem> items = idPage.getContent().stream()
                .map(id -> {
                    Video v = byId.get(id);
                    if (v == null) return null;
                    return SearchItem.builder()
                            .id(v.getId())
                            .type("VIDEO")
                            .titleCkb(title(v.getCkbContent() != null ? v.getCkbContent().getTitle() : null))
                            .titleKmr(title(v.getKmrContent() != null ? v.getKmrContent().getTitle() : null))
                            .descriptionCkb(snippet(v.getCkbContent() != null ? v.getCkbContent().getDescription() : null))
                            .descriptionKmr(snippet(v.getKmrContent() != null ? v.getKmrContent().getDescription() : null))
                            .coverUrl(firstNonNull(v.getCkbCoverUrl(), v.getKmrCoverUrl()))
                            .createdAt(v.getCreatedAt())
                            .build();
                })
                .filter(i -> i != null)
                .collect(Collectors.toList());

        return toSection(items, idPage, pageable);
    }

    // ── Writings ──────────────────────────────────────────────────────────────

    private SearchSection searchWritings(String q, Pageable pageable) {
        Page<Long> idPage = writingRepo.findIdsByGlobalSearch(q, pageable);
        if (idPage.isEmpty()) return SearchSection.empty(pageable.getPageNumber(), pageable.getPageSize());

        Map<Long, Writing> byId = writingRepo.findAllByIds(idPage.getContent())
                .stream()
                .collect(Collectors.toMap(Writing::getId, Function.identity()));

        List<SearchItem> items = idPage.getContent().stream()
                .map(id -> {
                    Writing w = byId.get(id);
                    if (w == null) return null;
                    return SearchItem.builder()
                            .id(w.getId())
                            .type("WRITING")
                            .titleCkb(title(w.getCkbContent() != null ? w.getCkbContent().getTitle() : null))
                            .titleKmr(title(w.getKmrContent() != null ? w.getKmrContent().getTitle() : null))
                            .descriptionCkb(snippet(w.getCkbContent() != null ? w.getCkbContent().getDescription() : null))
                            .descriptionKmr(snippet(w.getKmrContent() != null ? w.getKmrContent().getDescription() : null))
                            .coverUrl(firstNonNull(w.getCkbCoverUrl(), w.getKmrCoverUrl()))
                            .createdAt(w.getCreatedAt())
                            .build();
                })
                .filter(i -> i != null)
                .collect(Collectors.toList());

        return toSection(items, idPage, pageable);
    }

    // ── SoundTracks ───────────────────────────────────────────────────────────

    private SearchSection searchSoundTracks(String q, Pageable pageable) {
        Page<Long> idPage = soundTrackRepo.findIdsByGlobalSearch(q, pageable);
        if (idPage.isEmpty()) return SearchSection.empty(pageable.getPageNumber(), pageable.getPageSize());

        Map<Long, SoundTrack> byId = soundTrackRepo.findAllByIds(idPage.getContent())
                .stream()
                .collect(Collectors.toMap(SoundTrack::getId, Function.identity()));

        List<SearchItem> items = idPage.getContent().stream()
                .map(id -> {
                    SoundTrack s = byId.get(id);
                    if (s == null) return null;
                    return SearchItem.builder()
                            .id(s.getId())
                            .type("SOUNDTRACK")
                            .titleCkb(title(s.getCkbContent() != null ? s.getCkbContent().getTitle() : null))
                            .titleKmr(title(s.getKmrContent() != null ? s.getKmrContent().getTitle() : null))
                            .descriptionCkb(snippet(s.getCkbContent() != null ? s.getCkbContent().getDescription() : null))
                            .descriptionKmr(snippet(s.getKmrContent() != null ? s.getKmrContent().getDescription() : null))
                            .coverUrl(firstNonNull(s.getCkbCoverUrl(), s.getKmrCoverUrl()))
                            .createdAt(s.getCreatedAt())
                            .build();
                })
                .filter(i -> i != null)
                .collect(Collectors.toList());

        return toSection(items, idPage, pageable);
    }

    // ── ImageCollections ──────────────────────────────────────────────────────

    private SearchSection searchImageCollections(String q, Pageable pageable) {
        Page<Long> idPage = imageCollectionRepo.findIdsByGlobalSearch(q, pageable);
        if (idPage.isEmpty()) return SearchSection.empty(pageable.getPageNumber(), pageable.getPageSize());

        Map<Long, ImageCollection> byId = imageCollectionRepo.findAllByIds(idPage.getContent())
                .stream()
                .collect(Collectors.toMap(ImageCollection::getId, Function.identity()));

        List<SearchItem> items = idPage.getContent().stream()
                .map(id -> {
                    ImageCollection ic = byId.get(id);
                    if (ic == null) return null;
                    return SearchItem.builder()
                            .id(ic.getId())
                            .type("IMAGE")
                            .titleCkb(title(ic.getCkbContent() != null ? ic.getCkbContent().getTitle() : null))
                            .titleKmr(title(ic.getKmrContent() != null ? ic.getKmrContent().getTitle() : null))
                            .descriptionCkb(snippet(ic.getCkbContent() != null ? ic.getCkbContent().getDescription() : null))
                            .descriptionKmr(snippet(ic.getKmrContent() != null ? ic.getKmrContent().getDescription() : null))
                            .coverUrl(firstNonNull(ic.getCkbCoverUrl(), ic.getKmrCoverUrl()))
                            .createdAt(ic.getCreatedAt())
                            .build();
                })
                .filter(i -> i != null)
                .collect(Collectors.toList());

        return toSection(items, idPage, pageable);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE — helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns true when searching ALL types, or when the type matches the requested filter. */
    private boolean searchesType(String requested, String target) {
        return "ALL".equals(requested) || target.equals(requested);
    }

    /** Builds a SearchSection from a list of items + the original ID page for metadata. */
    private SearchSection toSection(List<SearchItem> items, Page<Long> idPage, Pageable pageable) {
        return SearchSection.builder()
                .items(items)
                .totalElements(idPage.getTotalElements())
                .totalPages(idPage.getTotalPages())
                .currentPage(pageable.getPageNumber())
                .size(pageable.getPageSize())
                .build();
    }

    /** Returns a title, or empty string if null. */
    private String title(String raw) {
        return raw != null ? raw : "";
    }

    /**
     * Returns a description snippet (max 200 chars).
     * Strips Tiptap HTML tags so search result cards stay clean.
     */
    private String snippet(String raw) {
        if (raw == null || raw.isBlank()) return "";
        // Strip tags + collapse whitespace; descriptions now contain Tiptap HTML.
        String plain = raw.replaceAll("<[^>]+>", " ")
                          .replaceAll("\\s+", " ")
                          .trim();
        if (plain.isEmpty()) return "";
        return plain.length() <= 200 ? plain : plain.substring(0, 200) + "…";
    }

    /** Returns the first non-null/non-blank string from a varargs list. */
    private String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}