package ak.dev.khi_backend.khi_app.service.project;

import ak.dev.khi_backend.khi_app.dto.project.ProjectCreateRequest;
import ak.dev.khi_backend.khi_app.dto.project.ProjectResponse;
import ak.dev.khi_backend.khi_app.enums.Language;
import ak.dev.khi_backend.khi_app.enums.MediaKind;
import ak.dev.khi_backend.khi_app.enums.project.ProjectStatus;
import ak.dev.khi_backend.khi_app.exceptions.*;
import ak.dev.khi_backend.khi_app.exceptions.project.*;
import ak.dev.khi_backend.khi_app.model.media.MediaItem;
import ak.dev.khi_backend.khi_app.model.project.*;
import ak.dev.khi_backend.khi_app.repository.project.*;
import ak.dev.khi_backend.khi_app.service.media.TiptapHtmlProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ProjectService — Tiptap migration.
 *
 * Cover image and inline media are no longer handled here. The frontend
 * uploads through {@code POST /api/v1/media/upload} and sends the resulting
 * URLs in the JSON body (the cover URL at the top level, inline assets baked
 * into the Tiptap HTML in {@code ckbContent.description} /
 * {@code kmrContent.description}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository          projectRepository;
    private final ProjectTagRepository       projectTagRepository;
    private final ProjectKeywordRepository   projectKeywordRepository;
    private final ProjectLogRepository       projectLogRepository;
    private final PlatformTransactionManager transactionManager;
    private final TiptapHtmlProcessor        tiptapHtmlProcessor;

    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    // ===========================
    // CREATE
    // ===========================
    @CacheEvict(value = "projects", allEntries = true)
    public Project create(ProjectCreateRequest dto) {
        String traceId = traceId();
        log.info("Create project | langs={} | traceId={}",
                dto != null ? dto.getContentLanguages() : null, traceId);

        validate(dto, true);

        try {
            Project saved = tx().execute(status -> {
                Project project = buildProject(dto);
                attachAllTags(project, dto);
                attachAllKeywords(project, dto);
                Project p = projectRepository.save(project);
                auditLog(p, "CREATE", "Project created: " + safeTitle(p));
                return p;
            });

            log.info("Project created | id={} | traceId={}", saved.getId(), traceId);
            return saved;

        } catch (AppException ex) { throw ex; }
        catch (DataIntegrityViolationException ex) {
            throw Errors.projectConflict(traceId);
        } catch (Exception ex) {
            log.error("Unexpected error creating project | traceId={}", traceId, ex);
            throw Errors.projectCreateFailed(traceId, ex);
        }
    }

    @CacheEvict(value = "projects", allEntries = true)
    public ProjectResponse createResponse(ProjectCreateRequest dto) {
        return toResponse(create(dto));
    }

    // ===========================
    // UPDATE
    // ===========================
    @CacheEvict(value = "projects", allEntries = true)
    public Project update(Long projectId, ProjectCreateRequest dto) {
        String traceId = traceId();
        log.info("Update project | id={} | traceId={}", projectId, traceId);

        validate(dto, true);

        try {
            Project saved = tx().execute(status -> {
                Project project = findOrThrow(projectId);
                applyUpdate(project, dto, dto.getCoverUrl());
                Project persisted = projectRepository.save(project);
                auditLog(persisted, "UPDATE", "Project updated: " + safeTitle(persisted));
                return persisted;
            });

            log.info("Project updated | id={} | traceId={}", saved.getId(), traceId);
            return saved;

        } catch (AppException ex) { throw ex; }
        catch (DataIntegrityViolationException ex) {
            throw Errors.projectConflict(traceId);
        } catch (Exception ex) {
            log.error("Error updating project | traceId={}", traceId, ex);
            throw Errors.projectUpdateFailed(projectId, traceId, ex);
        }
    }

    @CacheEvict(value = "projects", allEntries = true)
    public ProjectResponse updateResponse(Long projectId, ProjectCreateRequest dto) {
        return toResponse(update(projectId, dto));
    }

    // ===========================
    // DELETE
    // ===========================
    @CacheEvict(value = "projects", allEntries = true)
    @Transactional
    public void delete(Long projectId) {
        if (projectId == null) return;

        String traceId = traceId();
        log.info("Delete project | id={} | traceId={}", projectId, traceId);

        try {
            tx().execute(status -> {
                Project project = projectRepository.findById(projectId).orElse(null);
                if (project == null) {
                    log.debug("Project delete ignored; id={} does not exist", projectId);
                    return null;
                }
                String  title   = safeTitle(project);
                int logCount = projectLogRepository.deleteByProject(project);
                log.debug("{} project audit logs deleted for id={}", logCount, projectId);
                projectRepository.delete(project);
                log.info("Project deleted | id={} title='{}' | traceId={}", projectId, title, traceId);
                return null;
            });

        } catch (Exception ex) {
            log.error("Error deleting project | id={} | traceId={}", projectId, traceId, ex);
            throw Errors.projectDeleteFailed(projectId, traceId, ex);
        }
    }

    // ============================================================
    // READ
    // ============================================================

    @Cacheable(value = "projects", key = "'all:p' + #page + ':s' + #size")
    @Transactional(readOnly = true)
    public Page<ProjectResponse> getAllResponse(int page, int size) {
        Page<Long> idPage = projectRepository.findAllIds(PageRequest.of(page, size));

        if (idPage.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), idPage.getPageable(), idPage.getTotalElements());
        }

        List<Project> hydrated = hydrateAndSort(idPage.getContent());

        return new PageImpl<>(
                hydrated.stream().map(this::toResponse).collect(Collectors.toList()),
                idPage.getPageable(),
                idPage.getTotalElements()
        );
    }

    @Transactional(readOnly = true)
    public Page<ProjectResponse> getFeatured(int page, int size) {
        Pageable pageable = featuredPageable(page, size);
        return projectRepository.findByFeaturedTrue(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ProjectResponse getByIdResponse(Long projectId) {
        return toResponse(findOrThrow(projectId));
    }

    @Cacheable(value = "projects", key = "'tag:' + #tag.toLowerCase() + ':p' + #page + ':s' + #size")
    @Transactional(readOnly = true)
    public Page<ProjectResponse> searchByTagResponse(String tag, int page, int size) {
        if (isBlank(tag)) {
            throw new BadRequestException("tag.required", Map.of());
        }

        Page<Long> idPage = projectRepository.findIdsByTag(tag.trim(), PageRequest.of(page, size));

        if (idPage.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), idPage.getPageable(), idPage.getTotalElements());
        }

        List<Project> hydrated = hydrateAndSort(idPage.getContent());

        return new PageImpl<>(
                hydrated.stream().map(this::toResponse).collect(Collectors.toList()),
                idPage.getPageable(),
                idPage.getTotalElements()
        );
    }

    @Cacheable(value = "projects", key = "'kw:' + #keyword.toLowerCase() + ':p' + #page + ':s' + #size")
    @Transactional(readOnly = true)
    public Page<ProjectResponse> searchByKeywordResponse(String keyword, int page, int size) {
        if (isBlank(keyword)) {
            throw new BadRequestException("keyword.required", Map.of());
        }

        Page<Long> idPage = projectRepository.findIdsByKeyword(keyword.trim(), PageRequest.of(page, size));

        if (idPage.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), idPage.getPageable(), idPage.getTotalElements());
        }

        List<Project> hydrated = hydrateAndSort(idPage.getContent());

        return new PageImpl<>(
                hydrated.stream().map(this::toResponse).collect(Collectors.toList()),
                idPage.getPageable(),
                idPage.getTotalElements()
        );
    }

    @Cacheable(value = "projects", key = "'search:' + #q.toLowerCase() + ':p' + #page + ':s' + #size")
    @Transactional(readOnly = true)
    public Page<ProjectResponse> globalSearch(String q, int page, int size) {
        if (isBlank(q)) {
            throw new BadRequestException("keyword.required", Map.of());
        }

        Page<Long> idPage = projectRepository.findIdsByGlobalSearch(q.trim(), PageRequest.of(page, size));

        if (idPage.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), idPage.getPageable(), idPage.getTotalElements());
        }

        List<Project> hydrated = hydrateAndSort(idPage.getContent());

        return new PageImpl<>(
                hydrated.stream().map(this::toResponse).collect(Collectors.toList()),
                idPage.getPageable(),
                idPage.getTotalElements()
        );
    }

    private List<Project> hydrateAndSort(List<Long> ids) {
        List<Project> projects = projectRepository.findAllByIds(ids);

        Map<Long, Project> indexed = new LinkedHashMap<>(projects.size());
        for (Project p : projects) indexed.put(p.getId(), p);

        List<Project> ordered = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Project p = indexed.get(id);
            if (p != null) ordered.add(p);
        }
        return ordered;
    }

    // ============================================================
    // Build / update helpers
    // ============================================================

    private void applyUpdate(Project project, ProjectCreateRequest dto, String resolvedCoverUrl) {
        project.setCoverUrl(resolvedCoverUrl);
        project.setCoverMediaType(dto.getCoverMediaType() != null
                ? dto.getCoverMediaType() : MediaKind.IMAGE);
        project.setCoverThumbnailUrl(blankToNull(dto.getCoverThumbnailUrl()));
        project.setMediaGallery(buildGallery(dto.getMediaGallery()));
        project.setProjectDate(dto.getProjectDate());
        project.setProjectTypeCkb(dto.getProjectTypeCkb());
        project.setProjectTypeKmr(dto.getProjectTypeKmr());
        project.setStatus(dto.getStatus() != null ? dto.getStatus() : ProjectStatus.ONGOING);

        project.getContentLanguages().clear();
        if (dto.getContentLanguages() != null) {
            project.getContentLanguages().addAll(dto.getContentLanguages());
        }

        if (dto.getContentLanguages() != null && dto.getContentLanguages().contains(Language.CKB)) {
            project.setCkbContent(dto.getCkbContent() != null
                    ? ProjectContentBlock.builder()
                    .title(dto.getCkbContent().getTitle())
                    .description(tiptapHtmlProcessor.process(dto.getCkbContent().getDescription()))
                    .location(dto.getCkbContent().getLocation())
                    .build() : null);
        } else {
            project.setCkbContent(null);
        }

        if (dto.getContentLanguages() != null && dto.getContentLanguages().contains(Language.KMR)) {
            project.setKmrContent(dto.getKmrContent() != null
                    ? ProjectContentBlock.builder()
                    .title(dto.getKmrContent().getTitle())
                    .description(tiptapHtmlProcessor.process(dto.getKmrContent().getDescription()))
                    .location(dto.getKmrContent().getLocation())
                    .build() : null);
        } else {
            project.setKmrContent(null);
        }

        project.getTagsCkb().clear();
        project.getTagsKmr().clear();
        project.getKeywordsCkb().clear();
        project.getKeywordsKmr().clear();

        attachAllTags(project, dto);
        attachAllKeywords(project, dto);
    }

    private Project buildProject(ProjectCreateRequest dto) {
        Project project = new Project();
        applyBaseFields(project, dto);

        if (dto.getContentLanguages() != null && dto.getContentLanguages().contains(Language.CKB)
                && dto.getCkbContent() != null) {
            project.setCkbContent(ProjectContentBlock.builder()
                    .title(dto.getCkbContent().getTitle())
                    .description(tiptapHtmlProcessor.process(dto.getCkbContent().getDescription()))
                    .location(dto.getCkbContent().getLocation())
                    .build());
        }

        if (dto.getContentLanguages() != null && dto.getContentLanguages().contains(Language.KMR)
                && dto.getKmrContent() != null) {
            project.setKmrContent(ProjectContentBlock.builder()
                    .title(dto.getKmrContent().getTitle())
                    .description(tiptapHtmlProcessor.process(dto.getKmrContent().getDescription()))
                    .location(dto.getKmrContent().getLocation())
                    .build());
        }

        return project;
    }

    private void applyBaseFields(Project project, ProjectCreateRequest dto) {
        project.setCoverUrl(dto.getCoverUrl());
        project.setCoverMediaType(dto.getCoverMediaType() != null
                ? dto.getCoverMediaType() : MediaKind.IMAGE);
        project.setCoverThumbnailUrl(blankToNull(dto.getCoverThumbnailUrl()));
        project.setMediaGallery(buildGallery(dto.getMediaGallery()));
        project.setProjectTypeCkb(dto.getProjectTypeCkb());
        project.setProjectTypeKmr(dto.getProjectTypeKmr());
        project.setStatus(dto.getStatus() != null ? dto.getStatus() : ProjectStatus.ONGOING);
        project.setProjectDate(dto.getProjectDate());
        project.getContentLanguages().clear();
        if (dto.getContentLanguages() != null) {
            project.getContentLanguages().addAll(dto.getContentLanguages());
        }
    }

    private void validate(ProjectCreateRequest dto, boolean requireCoverUrl) {
        if (dto == null) throw ProjectValidationException.requestRequired();

        boolean hasCkb = dto.getContentLanguages() != null && dto.getContentLanguages().contains(Language.CKB);
        boolean hasKmr = dto.getContentLanguages() != null && dto.getContentLanguages().contains(Language.KMR);

        if (hasCkb && isBlank(dto.getProjectTypeCkb()))
            throw ProjectValidationException.ckbTypeRequired();
        if (hasKmr && isBlank(dto.getProjectTypeKmr()))
            throw ProjectValidationException.kmrTypeRequired();
        if (dto.getContentLanguages() == null || dto.getContentLanguages().isEmpty())
            throw ProjectValidationException.languagesRequired();
        if (requireCoverUrl && isBlank(dto.getCoverUrl()))
            throw ProjectValidationException.coverRequired();
        if (hasCkb && (dto.getCkbContent() == null || isBlank(dto.getCkbContent().getTitle())))
            throw ProjectValidationException.ckbTitleRequired();
        if (hasKmr && (dto.getKmrContent() == null || isBlank(dto.getKmrContent().getTitle())))
            throw ProjectValidationException.kmrTitleRequired();
    }

    private void attachAllTags(Project p, ProjectCreateRequest dto) {
        attachTags(p, dto.getTagsCkb(), Language.CKB);
        attachTags(p, dto.getTagsKmr(), Language.KMR);
    }

    private void attachTags(Project project, List<String> names, Language lang) {
        if (names == null || names.isEmpty()) return;
        Map<String, ProjectTag> map = ensureTags(names);
        Set<Long> seen = new HashSet<>();
        for (String raw : names) {
            ProjectTag t = map.get(normKey(raw));
            if (t != null && seen.add(t.getId())) {
                if (lang == Language.CKB) project.getTagsCkb().add(t);
                else                      project.getTagsKmr().add(t);
            }
        }
    }

    private Map<String, ProjectTag> ensureTags(List<String> names) {
        Map<String, ProjectTag> result = new HashMap<>();
        for (String raw : names) {
            String name = safe(raw).trim();
            if (name.isEmpty()) continue;
            result.put(name.toLowerCase(), projectTagRepository
                    .findByNameIgnoreCase(name)
                    .orElseGet(() -> projectTagRepository.save(
                            ProjectTag.builder().name(name).build())));
        }
        return result;
    }

    private void attachAllKeywords(Project p, ProjectCreateRequest dto) {
        attachKeywords(p, dto.getKeywordsCkb(), Language.CKB);
        attachKeywords(p, dto.getKeywordsKmr(), Language.KMR);
    }

    private void attachKeywords(Project project, List<String> names, Language lang) {
        if (names == null || names.isEmpty()) return;
        Map<String, ProjectKeyword> map = ensureKeywords(names);
        Set<Long> seen = new HashSet<>();
        for (String raw : names) {
            ProjectKeyword k = map.get(normKey(raw));
            if (k != null && seen.add(k.getId())) {
                if (lang == Language.CKB) project.getKeywordsCkb().add(k);
                else                      project.getKeywordsKmr().add(k);
            }
        }
    }

    private Map<String, ProjectKeyword> ensureKeywords(List<String> names) {
        Map<String, ProjectKeyword> result = new HashMap<>();
        for (String raw : names) {
            String name = safe(raw).trim();
            if (name.isEmpty()) continue;
            result.put(name.toLowerCase(), projectKeywordRepository
                    .findByNameIgnoreCase(name)
                    .orElseGet(() -> projectKeywordRepository.save(
                            ProjectKeyword.builder().name(name).build())));
        }
        return result;
    }

    // ============================================================
    // toResponse
    // ============================================================
    private ProjectResponse toResponse(Project project) {
        ProjectResponse.ProjectContentBlockDto ckb = null;
        if (project.getCkbContent() != null) {
            ckb = ProjectResponse.ProjectContentBlockDto.builder()
                    .title(project.getCkbContent().getTitle())
                    .description(project.getCkbContent().getDescription())
                    .location(project.getCkbContent().getLocation())
                    .build();
        }

        ProjectResponse.ProjectContentBlockDto kmr = null;
        if (project.getKmrContent() != null) {
            kmr = ProjectResponse.ProjectContentBlockDto.builder()
                    .title(project.getKmrContent().getTitle())
                    .description(project.getKmrContent().getDescription())
                    .location(project.getKmrContent().getLocation())
                    .build();
        }

        List<String> tagsCkb = toNames(project.getTagsCkb(), ProjectTag::getName);
        List<String> tagsKmr = toNames(project.getTagsKmr(), ProjectTag::getName);
        List<String> keysCkb = toNames(project.getKeywordsCkb(), ProjectKeyword::getName);
        List<String> keysKmr = toNames(project.getKeywordsKmr(), ProjectKeyword::getName);

        Instant createdAt = project.getCreatedAt() != null
                ? project.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant()
                : null;

        return ProjectResponse.builder()
                .id(project.getId())
                .coverUrl(project.getCoverUrl())
                .coverMediaType(project.getCoverMediaType() != null
                        ? project.getCoverMediaType() : MediaKind.IMAGE)
                .coverThumbnailUrl(project.getCoverThumbnailUrl())
                .featureImageUrl(project.getFeatureImageUrl())
                .mediaGallery(project.getMediaGallery() != null
                        ? new ArrayList<>(project.getMediaGallery())
                        : new ArrayList<>())
                .projectTypeCkb(project.getProjectTypeCkb())
                .projectTypeKmr(project.getProjectTypeKmr())
                .status(project.getStatus())
                .projectDate(project.getProjectDate())
                .contentLanguages(
                        project.getContentLanguages() != null
                                ? new LinkedHashSet<>(project.getContentLanguages())
                                : new LinkedHashSet<>()
                )
                .ckbContent(ckb)
                .kmrContent(kmr)
                .tagsCkb(tagsCkb)
                .tagsKmr(tagsKmr)
                .keywordsCkb(keysCkb)
                .keywordsKmr(keysKmr)
                .createdAt(createdAt)
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

    // ============================================================
    // Audit Log
    // ============================================================
    private void auditLog(Project project, String action, String message) {
        try {
            projectLogRepository.save(ProjectLog.builder()
                    .project(project)
                    .action(action)
                    .fieldName("SUMMARY")
                    .oldValue(null)
                    .newValue(message)
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("Failed to write project audit log | projectId={} | action={}",
                    project != null ? project.getId() : null, action, e);
        }
    }

    // ============================================================
    // Utils
    // ============================================================
    private Project findOrThrow(Long projectId) {
        return projectRepository.findByIdWithGraph(projectId)
                .orElseThrow(() -> Errors.projectNotFound(projectId));
    }

    @FunctionalInterface
    private interface NameExtractor<T> { String name(T t); }

    private <T> List<String> toNames(Iterable<T> items, NameExtractor<T> fn) {
        List<String> out = new ArrayList<>();
        if (items == null) return out;
        for (T item : items) {
            if (item != null && !isBlank(fn.name(item))) out.add(fn.name(item));
        }
        return out;
    }

    private String traceId()           { String t = MDC.get("traceId"); return t != null ? t : UUID.randomUUID().toString(); }
    private boolean isBlank(String s)  { return s == null || s.trim().isEmpty(); }
    private String safe(Object o)      { return o == null ? "" : String.valueOf(o); }
    private String normKey(String raw) { return safe(raw).trim().toLowerCase(); }
    private String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private List<MediaItem> buildGallery(List<MediaItem> gallery) {
        if (gallery == null || gallery.isEmpty()) return new ArrayList<>();
        ArrayList<MediaItem> result = new ArrayList<>();
        int idx = 0;
        for (MediaItem item : gallery) {
            if (item == null || isBlank(item.getUrl())) continue;
            result.add(MediaItem.builder()
                    .url(item.getUrl().trim())
                    .kind(item.getKind() != null ? item.getKind() : MediaKind.IMAGE)
                    .thumbnailUrl(blankToNull(item.getThumbnailUrl()))
                    .captionCkb(blankToNull(item.getCaptionCkb()))
                    .captionKmr(blankToNull(item.getCaptionKmr()))
                    .sortOrder(item.getSortOrder() != null ? item.getSortOrder() : idx)
                    .build());
            idx++;
        }
        result.sort(Comparator.comparingInt(
                m -> m.getSortOrder() != null ? m.getSortOrder() : Integer.MAX_VALUE));
        return result;
    }

    private String safeTitle(Project p) {
        if (p == null) return "";
        if (p.getCkbContent() != null && !isBlank(p.getCkbContent().getTitle())) return p.getCkbContent().getTitle();
        if (p.getKmrContent() != null && !isBlank(p.getKmrContent().getTitle())) return p.getKmrContent().getTitle();
        return "project#" + p.getId();
    }
}
