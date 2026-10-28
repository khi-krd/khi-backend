package ak.dev.khi_backend.khi_app.service.news;

import ak.dev.khi_backend.khi_app.dto.news.NewsTaxonomyDtos.CategoryRequest;
import ak.dev.khi_backend.khi_app.dto.news.NewsTaxonomyDtos.CategoryResponse;
import ak.dev.khi_backend.khi_app.dto.news.NewsTaxonomyDtos.SubCategoryRequest;
import ak.dev.khi_backend.khi_app.dto.news.NewsTaxonomyDtos.SubCategoryResponse;
import ak.dev.khi_backend.khi_app.exceptions.BadRequestException;
import ak.dev.khi_backend.khi_app.exceptions.ResourceNotFoundException;
import ak.dev.khi_backend.khi_app.model.news.NewsCategory;
import ak.dev.khi_backend.khi_app.model.news.NewsSubCategory;
import ak.dev.khi_backend.khi_app.repository.news.NewsCategoryRepository;
import ak.dev.khi_backend.khi_app.repository.news.NewsRepository;
import ak.dev.khi_backend.khi_app.repository.news.NewsSubCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Admin CRUD for the news category / sub-category vocabulary.
 *
 * News rows hold required FKs into both tables, so deletes cannot simply
 * remove the row: any news still pointing at the doomed bucket is first
 * reassigned to a fallback — the "گشتی/Giştî" General bucket when it exists,
 * otherwise the first remaining category (with a freshly minted General
 * bucket as last resort). The same fallback pair is used for the
 * sub-category column so the category/subCategory pair on a news row stays
 * consistent.
 */
@Service
@RequiredArgsConstructor
public class NewsTaxonomyService {

    private static final String GENERAL_CKB = "گشتی";
    private static final String GENERAL_KMR = "Giştî";
    private static final String OTHER_CKB = "هیتر";
    private static final String OTHER_KMR = "Yên din";

    private final NewsCategoryRepository categoryRepository;
    private final NewsSubCategoryRepository subCategoryRepository;
    private final NewsRepository newsRepository;

    // ───────────────────────────────────────────── read ──

    @Transactional(readOnly = true)
    public List<CategoryResponse> list() {
        return categoryRepository.findAllByOrderByNameCkbAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    // ───────────────────────────────────────────── categories ──

    @Transactional
    @CacheEvict(value = "news", allEntries = true)
    public CategoryResponse createCategory(CategoryRequest request) {
        String ckb = requireName(request == null ? null : request.getNameCkb());
        String kmr = kmrOr(request == null ? null : request.getNameKmr(), "");
        categoryRepository.findByNameCkb(ckb).ifPresent(existing -> {
            throw new BadRequestException("news.category.duplicate", ckb);
        });
        return toResponse(categoryRepository.save(NewsCategory.builder()
                .nameCkb(ckb)
                .nameKmr(kmr)
                .build()));
    }

    @Transactional
    @CacheEvict(value = "news", allEntries = true)
    public CategoryResponse updateCategory(Long id, CategoryRequest request) {
        NewsCategory category = findCategory(id);
        String ckb = requireName(request == null ? null : request.getNameCkb());
        String kmr = kmrOr(request == null ? null : request.getNameKmr(), "");
        categoryRepository.findByNameCkb(ckb).ifPresent(existing -> {
            if (!existing.getId().equals(id)) {
                throw new BadRequestException("news.category.duplicate", ckb);
            }
        });
        category.setNameCkb(ckb);
        category.setNameKmr(kmr);
        return toResponse(categoryRepository.save(category));
    }

    @Transactional
    @CacheEvict(value = "news", allEntries = true)
    public void deleteCategory(Long id) {
        NewsCategory category = findCategory(id);
        if (newsRepository.countByCategory_Id(id) > 0) {
            NewsCategory fallback = ensureFallbackCategory(id);
            NewsSubCategory fallbackSub = ensureFallbackSub(fallback, null);
            for (NewsSubCategory sub : subCategoryRepository
                    .findByCategory_IdOrderByNameCkbAsc(id)) {
                newsRepository.reassignSubCategory(sub.getId(), fallbackSub);
            }
            newsRepository.reassignCategory(id, fallback);
        }
        categoryRepository.delete(category);
    }

    // ───────────────────────────────────────────── sub-categories ──

    @Transactional
    @CacheEvict(value = "news", allEntries = true)
    public SubCategoryResponse createSubCategory(SubCategoryRequest request) {
        String ckb = requireName(request == null ? null : request.getNameCkb());
        String kmr = kmrOr(request == null ? null : request.getNameKmr(), "");
        NewsCategory category = resolveCategory(request);
        subCategoryRepository.findByCategoryAndNameCkb(category, ckb).ifPresent(existing -> {
            throw new BadRequestException("news.subcategory.duplicate", ckb);
        });
        return toResponse(subCategoryRepository.save(NewsSubCategory.builder()
                .nameCkb(ckb)
                .nameKmr(kmr)
                .category(category)
                .build()));
    }

    @Transactional
    @CacheEvict(value = "news", allEntries = true)
    public SubCategoryResponse updateSubCategory(Long id, SubCategoryRequest request) {
        NewsSubCategory sub = findSubCategory(id);
        String ckb = requireName(request == null ? null : request.getNameCkb());
        String kmr = kmrOr(request == null ? null : request.getNameKmr(), "");
        NewsCategory target = request != null && request.getCategoryId() != null
                ? findCategory(request.getCategoryId())
                : sub.getCategory();
        subCategoryRepository.findByCategoryAndNameCkb(target, ckb).ifPresent(existing -> {
            if (!existing.getId().equals(id)) {
                throw new BadRequestException("news.subcategory.duplicate", ckb);
            }
        });
        sub.setNameCkb(ckb);
        sub.setNameKmr(kmr);
        sub.setCategory(target);
        return toResponse(subCategoryRepository.save(sub));
    }

    @Transactional
    @CacheEvict(value = "news", allEntries = true)
    public void deleteSubCategory(Long id) {
        NewsSubCategory sub = findSubCategory(id);
        if (newsRepository.countBySubCategory_Id(id) > 0) {
            NewsSubCategory fallback = ensureFallbackSub(sub.getCategory(), id);
            newsRepository.reassignSubCategory(id, fallback);
        }
        subCategoryRepository.delete(sub);
    }

    // ───────────────────────────────────────────── helpers ──

    private CategoryResponse toResponse(NewsCategory category) {
        return CategoryResponse.builder()
                .id(category.getId())
                .nameCkb(category.getNameCkb())
                .nameKmr(category.getNameKmr())
                .newsCount(newsRepository.countByCategory_Id(category.getId()))
                .subCategories(subCategoryRepository
                        .findByCategory_IdOrderByNameCkbAsc(category.getId())
                        .stream()
                        .map(this::toResponse)
                        .toList())
                .build();
    }

    private SubCategoryResponse toResponse(NewsSubCategory sub) {
        return SubCategoryResponse.builder()
                .id(sub.getId())
                .nameCkb(sub.getNameCkb())
                .nameKmr(sub.getNameKmr())
                .categoryId(sub.getCategory().getId())
                .newsCount(newsRepository.countBySubCategory_Id(sub.getId()))
                .build();
    }

    private NewsCategory findCategory(Long id) {
        if (id == null) {
            throw new BadRequestException("news.category.required");
        }
        return categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("news.category.notFound", id));
    }

    /**
     * Parent lookup for a sub-category write: by id when the caller has it,
     * else by name — auto-creating the parent (as the news save path does)
     * when the name is not in the vocabulary yet.
     */
    private NewsCategory resolveCategory(SubCategoryRequest request) {
        if (request != null && request.getCategoryId() != null) {
            return findCategory(request.getCategoryId());
        }
        String ckb = requireName(request == null ? null : request.getCategoryCkb());
        return categoryRepository.findByNameCkb(ckb)
                .orElseGet(() -> categoryRepository.save(NewsCategory.builder()
                        .nameCkb(ckb)
                        .nameKmr(kmrOr(request.getCategoryKmr(), ""))
                        .build()));
    }

    private NewsSubCategory findSubCategory(Long id) {
        return subCategoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("news.subcategory.notFound", id));
    }

    private String requireName(String raw) {
        String ckb = raw == null ? "" : raw.trim();
        if (ckb.isEmpty()) {
            throw new BadRequestException("news.category.nameRequired");
        }
        return ckb;
    }

    private String kmrOr(String raw, String fallback) {
        String kmr = raw == null ? "" : raw.trim();
        return kmr.isEmpty() ? fallback : kmr;
    }

    /**
     * The bucket orphaned news should land in: the General category when it
     * is not the one being deleted, else the first other category, else a
     * freshly minted bucket (using the Other label when the doomed row has
     * already claimed "گشتی").
     */
    private NewsCategory ensureFallbackCategory(Long excludingId) {
        List<NewsCategory> all = categoryRepository.findAllByOrderByNameCkbAsc();
        List<NewsCategory> others = all.stream()
                .filter(c -> !c.getId().equals(excludingId))
                .toList();
        return others.stream()
                .filter(c -> GENERAL_CKB.equals(c.getNameCkb()))
                .findFirst()
                .or(() -> others.stream().findFirst())
                .orElseGet(() -> {
                    boolean generalTaken = all.stream()
                            .anyMatch(c -> GENERAL_CKB.equals(c.getNameCkb()));
                    return categoryRepository.save(NewsCategory.builder()
                            .nameCkb(generalTaken ? OTHER_CKB : GENERAL_CKB)
                            .nameKmr(generalTaken ? OTHER_KMR : GENERAL_KMR)
                            .build());
                });
    }

    /**
     * Same idea inside one category: prefer the General sub, else any other
     * sub, else mint one (Other label when General is already taken — e.g.
     * when the doomed row IS the General sub).
     */
    private NewsSubCategory ensureFallbackSub(NewsCategory category, Long excludingId) {
        List<NewsSubCategory> subs = subCategoryRepository
                .findByCategory_IdOrderByNameCkbAsc(category.getId());
        List<NewsSubCategory> others = excludingId == null
                ? subs
                : subs.stream().filter(s -> !s.getId().equals(excludingId)).toList();
        return others.stream()
                .filter(s -> GENERAL_CKB.equals(s.getNameCkb()))
                .findFirst()
                .or(() -> others.stream().findFirst())
                .orElseGet(() -> {
                    boolean generalTaken = subs.stream()
                            .anyMatch(s -> GENERAL_CKB.equals(s.getNameCkb()));
                    return subCategoryRepository.save(NewsSubCategory.builder()
                            .nameCkb(generalTaken ? OTHER_CKB : GENERAL_CKB)
                            .nameKmr(generalTaken ? OTHER_KMR : GENERAL_KMR)
                            .category(category)
                            .build());
                });
    }
}
