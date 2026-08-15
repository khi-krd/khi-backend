package ak.dev.khi_backend.khi_app.service.site;

import ak.dev.khi_backend.khi_app.dto.site.NavMenuDtos.*;
import ak.dev.khi_backend.khi_app.exceptions.ConflictException;
import ak.dev.khi_backend.khi_app.exceptions.NotFoundException;
import ak.dev.khi_backend.khi_app.model.site.NavMenuItem;
import ak.dev.khi_backend.khi_app.model.site.NavMenuLink;
import ak.dev.khi_backend.khi_app.repository.site.NavMenuItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Website hamburger menu — ten top-level items, each with its own background photo
 * and up to a handful of secondary links.
 *
 * <p>One private {@link #apply} is shared by create and update, the same shape
 * {@code SiteContentService} uses for team members.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NavMenuService {

    private final NavMenuItemRepository repository;

    /**
     * @param includeInactive dashboard passes {@code true} to see hidden rows;
     *                        the website leaves it {@code false} and also gets
     *                        inactive links filtered out.
     */
    @Transactional(readOnly = true)
    public List<NavMenuItemResponse> list(boolean includeInactive) {
        List<NavMenuItem> items = includeInactive
                ? repository.findAllByOrderByDisplayOrderAscIdAsc()
                : repository.findAllByActiveTrueOrderByDisplayOrderAscIdAsc();
        return items.stream().map(item -> toResponse(item, includeInactive)).toList();
    }

    /** Feeds the dashboard edit form, so inactive links are kept. */
    @Transactional(readOnly = true)
    public NavMenuItemResponse get(Long id) {
        return toResponse(repository.findById(id).orElseThrow(() -> notFound(id)), true);
    }

    @Transactional
    public NavMenuItemResponse create(NavMenuItemRequest request) {
        String itemKey = normalizeKey(request.getItemKey());
        if (repository.existsByItemKeyIgnoreCase(itemKey)) {
            throw duplicateKey(itemKey);
        }
        NavMenuItem item = new NavMenuItem();
        apply(item, request);
        NavMenuItem saved = repository.save(item);
        log.info("Nav menu item created | id={} | itemKey={}", saved.getId(), saved.getItemKey());
        return toResponse(saved, true);
    }

    @Transactional
    public NavMenuItemResponse update(Long id, NavMenuItemRequest request) {
        NavMenuItem item = repository.findById(id).orElseThrow(() -> notFound(id));
        String itemKey = normalizeKey(request.getItemKey());
        if (repository.existsByItemKeyIgnoreCaseAndIdNot(itemKey, id)) {
            throw duplicateKey(itemKey);
        }
        apply(item, request);
        NavMenuItem saved = repository.save(item);
        log.info("Nav menu item updated | id={} | itemKey={}", saved.getId(), saved.getItemKey());
        return toResponse(saved, true);
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw notFound(id);
        }
        repository.deleteById(id);
        log.info("Nav menu item deleted | id={}", id);
    }

    private void apply(NavMenuItem item, NavMenuItemRequest request) {
        item.setItemKey(normalizeKey(request.getItemKey()));
        item.setLabelCkb(request.getLabelCkb().trim());
        item.setLabelKmr(trimToNull(request.getLabelKmr()));
        item.setDescriptionCkb(trimToNull(request.getDescriptionCkb()));
        item.setDescriptionKmr(trimToNull(request.getDescriptionKmr()));
        item.setHref(request.getHref().trim());
        item.setImageUrl(trimToNull(request.getImageUrl()));
        item.setDisplayOrder(request.getDisplayOrder() == null ? 0 : request.getDisplayOrder());
        item.setActive(request.getActive() == null || request.getActive());

        if (request.getLinks() == null) {
            return;                              // null = leave the links alone
        }

        item.getLinks().clear();                 // orphanRemoval deletes the old rows
        List<NavMenuLinkRequest> requested = request.getLinks();
        for (int i = 0; i < requested.size(); i++) {
            NavMenuLinkRequest linkRequest = requested.get(i);
            NavMenuLink link = new NavMenuLink();
            link.setItem(item);
            link.setLabelCkb(linkRequest.getLabelCkb().trim());
            link.setLabelKmr(trimToNull(linkRequest.getLabelKmr()));
            link.setHref(linkRequest.getHref().trim());
            link.setDisplayOrder(linkRequest.getDisplayOrder() == null ? i + 1 : linkRequest.getDisplayOrder());
            link.setActive(linkRequest.getActive() == null || linkRequest.getActive());
            item.getLinks().add(link);
        }
    }

    private NavMenuItemResponse toResponse(NavMenuItem item, boolean includeInactiveLinks) {
        List<NavMenuLinkResponse> links = item.getLinks().stream()
                .filter(link -> includeInactiveLinks || link.isActive())
                .map(this::toResponse)
                .toList();
        return NavMenuItemResponse.builder()
                .id(item.getId())
                .itemKey(item.getItemKey())
                .labelCkb(item.getLabelCkb())
                .labelKmr(item.getLabelKmr())
                .descriptionCkb(item.getDescriptionCkb())
                .descriptionKmr(item.getDescriptionKmr())
                .href(item.getHref())
                .imageUrl(item.getImageUrl())
                .displayOrder(item.getDisplayOrder())
                .active(item.isActive())
                .links(links)
                .build();
    }

    private NavMenuLinkResponse toResponse(NavMenuLink link) {
        return NavMenuLinkResponse.builder()
                .id(link.getId())
                .labelCkb(link.getLabelCkb())
                .labelKmr(link.getLabelKmr())
                .href(link.getHref())
                .displayOrder(link.getDisplayOrder())
                .active(link.isActive())
                .build();
    }

    private String normalizeKey(String itemKey) {
        return itemKey.trim().toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ConflictException duplicateKey(String itemKey) {
        return new ConflictException("navMenu.itemKey.duplicate", Map.of("itemKey", itemKey));
    }

    private NotFoundException notFound(Long id) {
        return new NotFoundException("navMenu.not_found", Map.of("id", id));
    }
}
