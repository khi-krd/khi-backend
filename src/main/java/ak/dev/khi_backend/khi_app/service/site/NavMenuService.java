package ak.dev.khi_backend.khi_app.service.site;

import ak.dev.khi_backend.khi_app.dto.site.NavMenuDtos.*;
import ak.dev.khi_backend.khi_app.exceptions.NotFoundException;
import ak.dev.khi_backend.khi_app.model.site.NavMenuItem;
import ak.dev.khi_backend.khi_app.repository.site.NavMenuItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Website hamburger menu — a fixed set of sections whose only CMS-managed field is
 * the full-screen background photo. The website owns the section list, its labels
 * and its links from static config, so there is deliberately no create/delete:
 * rows exist per section and only {@link #update} (image + visibility) changes them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NavMenuService {

    private final NavMenuItemRepository repository;

    /**
     * @param includeInactive dashboard passes {@code true} to see hidden rows;
     *                        the website leaves it {@code false}.
     */
    @Transactional(readOnly = true)
    public List<NavMenuItemResponse> list(boolean includeInactive) {
        List<NavMenuItem> items = includeInactive
                ? repository.findAllByOrderByDisplayOrderAscIdAsc()
                : repository.findAllByActiveTrueOrderByDisplayOrderAscIdAsc();
        return items.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public NavMenuItemResponse get(Long id) {
        return toResponse(repository.findById(id).orElseThrow(() -> notFound(id)));
    }

    /**
     * Image-only update: {@code imageUrl} replaces the photo (blank/null clears it),
     * {@code active} toggles whether the website may use it (null = unchanged).
     * Every other column is read-only reference data and is never touched here.
     */
    @Transactional
    public NavMenuItemResponse update(Long id, NavMenuItemRequest request) {
        NavMenuItem item = repository.findById(id).orElseThrow(() -> notFound(id));
        item.setImageUrl(trimToNull(request.getImageUrl()));
        if (request.getActive() != null) {
            item.setActive(request.getActive());
        }
        NavMenuItem saved = repository.save(item);
        log.info("Nav menu image updated | id={} | itemKey={} | image={}",
                saved.getId(), saved.getItemKey(),
                saved.getImageUrl() == null ? "cleared" : "set");
        return toResponse(saved);
    }

    private NavMenuItemResponse toResponse(NavMenuItem item) {
        return NavMenuItemResponse.builder()
                .id(item.getId())
                .itemKey(item.getItemKey())
                .labelCkb(item.getLabelCkb())
                .labelKmr(item.getLabelKmr())
                .href(item.getHref())
                .imageUrl(item.getImageUrl())
                .displayOrder(item.getDisplayOrder())
                .active(item.isActive())
                .build();
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private NotFoundException notFound(Long id) {
        return new NotFoundException("navMenu.not_found", Map.of("id", id));
    }
}
